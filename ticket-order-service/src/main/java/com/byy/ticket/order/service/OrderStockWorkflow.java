package com.byy.ticket.order.service;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.common.trace.PerformanceSpan;
import com.byy.ticket.order.client.InventoryClient;
import com.byy.ticket.order.client.dto.StockReservationRequest;
import com.byy.ticket.order.client.dto.StockReservationResponse;
import com.byy.ticket.order.client.exception.InventoryServiceCallException;
import com.byy.ticket.order.config.OrderWorkflowProperties;
import com.byy.ticket.order.mapper.OrderMapper;
import com.byy.ticket.order.mapper.OrderItemMapper;
import com.byy.ticket.order.model.TicketOrder;
import com.byy.ticket.order.model.OrderItem;
import com.byy.ticket.order.model.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 下单、到期、付款及出票恢复状态机。HTTP在数据库事务外执行，出票使用独立本地事务。
 * 请求、恢复任务共用此流程；领取令牌防止旧任务覆盖新进度，库存幂等防止重复扣减。
 */
@Service
public class OrderStockWorkflow {
    private static final Logger log = LoggerFactory.getLogger(OrderStockWorkflow.class);
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final InventoryClient inventory;
    private final OrderWorkflowProperties properties;
    private final Clock clock;
    private final OrderPaymentFulfillment fulfillment;
    private final OrderTicketIssueTransaction ticketIssue;
    private final OrderTransactionService transactions;

    /** 注入订单持久化、库存客户端、付款履约步骤、出票事务、配置和业务时钟。 */
    public OrderStockWorkflow(OrderMapper orders, OrderItemMapper items, InventoryClient inventory,
                              OrderWorkflowProperties properties, Clock clock, OrderPaymentFulfillment fulfillment,
                              OrderTicketIssueTransaction ticketIssue, OrderTransactionService transactions) {
        this.orders = orders;
        this.items = items;
        this.inventory = inventory;
        this.properties = properties;
        this.clock = clock;
        this.fulfillment = fulfillment;
        this.ticketIssue = ticketIssue;
        this.transactions = transactions;
    }

    /** 按截止时间限量扫描，单条失败不会阻断后续订单；多实例再通过 claim 竞争领取。 */
    public void recoverDue() {
        for (Long id : orders.selectDue(properties.batchSize())) {
            TraceIdContext.setOrCreate(null);
            try { advance(id); }
            catch (RuntimeException exception) {
                log.error("订单恢复失败，orderId={}；租约到期后可继续领取", id, exception);
            } finally {
                // 调度线程会复用，每张订单使用独立 traceId，完成后清理。
                TraceIdContext.clear();
            }
        }
    }

    /** 领取一张订单并推进；这里不加 Transactional，不能跨 HTTP 长时间持有订单数据库锁。 */
    public void advance(Long id) {
        String token = UUID.randomUUID().toString().replace("-", "");
        if (PerformanceSpan.measure("workflow.claim",()->orders.claim(id, token, properties.leaseDuration().toNanos() / 1000)) != 1) { return; }
        TicketOrder order = PerformanceSpan.measure("workflow.lookup",()->orders.selectById(id));
        if (order == null || !token.equals(order.getLeaseToken())) { return; }
        try {
            if (OrderStatus.STOCK_PENDING.name().equals(order.getStatus())) {
                reserve(order, token);
            } else if (OrderStatus.PAYMENT_CONFIRMING.name().equals(order.getStatus())) {
                fulfillment.confirm(order, token);
            } else if (OrderStatus.REVERSAL_PENDING.name().equals(order.getStatus())) {
                fulfillment.reverse(order, token);
            } else if (OrderStatus.PAID.name().equals(order.getStatus())) {
                ticketIssue.issue(id,token);
            } else {
                close(order, token);
            }
        } catch (IllegalArgumentException | ResourceNotFoundException exception) {
            rejectOrRetry(order, token, exception);
        } catch (InventoryServiceCallException exception) {
            if (exception.getReason() == InventoryServiceCallException.Reason.CONFLICT
                    && !OrderStatus.PAYMENT_CONFIRMING.name().equals(order.getStatus())) {
                rejectOrRetry(order, token, exception);
            } else {
                retry(order, token, exception);
            }
        } catch (RuntimeException exception) {
            // DB 更新失败也不能推断库存未执行；保留或延后原步骤，进程崩溃则靠租约恢复。
            retry(order, token, exception);
        }
    }

    /** 用数据库快照构造完全相同的预留请求；到期后仍调用，以确认之前是否已经预留。 */
    private void reserve(TicketOrder order, String token) {
        OrderItem item = items.selectByOrderId(order.getId());
        if (item == null) {
            complete(order, token, OrderStatus.REVIEW_REQUIRED, null, "订单缺少购买快照");
            return;
        }
        StockReservationResponse result = PerformanceSpan.measure("inventory.http",()->inventory.reserve(new StockReservationRequest(order.getOrderNo(),
                item.getSessionId(), item.getTicketTierId(), item.getQuantity(), order.getExpiresAt())));
        order.setReservationId(result.reservationId());
        if ("RELEASED".equals(result.status())) {
            complete(order, token, OrderStatus.CLOSED, result.reservationId(), null);
        } else if ("SOLD".equals(result.status())) {
            complete(order, token, OrderStatus.REVIEW_REQUIRED, result.reservationId(), "库存已售出，需核对支付证据");
        } else if (!order.getExpiresAt().isAfter(now())) {
            close(order, token);
        } else {
            PerformanceSpan.measure("workflow.finish",()->orders.finish(order.getId(), token, order.getStatus(), OrderStatus.PENDING_PAYMENT.name(),
                    result.reservationId(), order.getExpiresAt(), null));
        }
    }

    /** 持久化关闭进度再释放；付款接收会调整状态并废止令牌，已进入关闭的付款保留供核对。 */
    private void close(TicketOrder order, String token) {
        if (OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus()) && order.getExpiresAt().isAfter(now())) {
            orders.finish(order.getId(), token, order.getStatus(), order.getStatus(), order.getReservationId(),
                    order.getExpiresAt(), null);
            return;
        }
        if (order.getReservationId() == null) {
            complete(order, token, OrderStatus.REVIEW_REQUIRED, null, "关闭订单缺少预留编号");
            return;
        }
        if (fulfillment.recordPaymentBeforeClose(order)) { return; }
        if (!OrderStatus.CLOSING.name().equals(order.getStatus())) {
            if (orders.beginClosing(order.getId(), token, order.getStatus(), order.getReservationId()) != 1) { return; }
            order.setStatus(OrderStatus.CLOSING.name());
        }
        StockReservationResponse released = inventory.release(order.getReservationId());
        complete(order, token, OrderStatus.CLOSED, released.reservationId(), null);
    }

    /**
     * 明确拒绝首次预留才标记创建失败；已有不确定尝试且尚未到期时继续核对，
     * 避免迟到请求随后完成预留而留下库存。关闭冲突保留 REVIEW_REQUIRED，不伪装成功。
     */
    private void rejectOrRetry(TicketOrder order, String token, RuntimeException exception) {
        if (OrderStatus.STOCK_PENDING.name().equals(order.getStatus())) {
            if (order.getAttemptCount() == 1
                    || (!order.getExpiresAt().isAfter(now()) && exception instanceof IllegalArgumentException)) {
                complete(order, token, OrderStatus.CREATE_FAILED, order.getReservationId(), summary(exception));
            } else if (!order.getExpiresAt().isAfter(now())) {
                // 到期后仍冲突或资源丢失，不推断不存在预留：保留证据供人工核对。
                complete(order, token, OrderStatus.REVIEW_REQUIRED, order.getReservationId(), summary(exception));
            } else {
                retry(order, token, exception);
            }
        } else {
            complete(order, token, OrderStatus.REVIEW_REQUIRED, order.getReservationId(), summary(exception));
        }
    }

    /** 指数退避持久化到订单表，不在请求线程 sleep，也不换订单编号或重算到期时间。 */
    private void retry(TicketOrder order, String token, RuntimeException exception) {
        long factor = 1L << Math.min(Math.max(order.getAttemptCount() - 1, 0), 10);
        long millis = Math.min(properties.retryMax().toMillis(), properties.retryBase().toMillis() * factor);
        orders.finish(order.getId(), token, order.getStatus(), order.getStatus(), order.getReservationId(),
                now().plus(millis, ChronoUnit.MILLIS), summary(exception));
        log.warn("订单步骤待核对，orderNo={}, status={}, attempt={}, cause={}",
                order.getOrderNo(), order.getStatus(), order.getAttemptCount(), exception.getClass().getSimpleName());
    }

    /** 按当前状态和领取令牌完成步骤；终态不再进入后台扫描。 */
    private void complete(TicketOrder order, String token, OrderStatus target, String reservationId, String error) {
        int changed = (target == OrderStatus.CLOSED || target == OrderStatus.CREATE_FAILED)
                ? transactions.finishAndReleaseQuota(order.getId(), token, order.getStatus(), target, reservationId, now(), error)
                : orders.finish(order.getId(), token, order.getStatus(), target.name(), reservationId, now(), error);
        if (changed == 1 && target == OrderStatus.REVIEW_REQUIRED) {
            log.error("订单需要核对，orderNo={}, reservationId={}, cause={}", order.getOrderNo(), reservationId, error);
        }
    }

    /** 固定 UTC+8 时钟并截断到毫秒，保证重试参数与 DATETIME(3) 一致。 */
    private LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS); }

    /** 只持久化有界错误摘要，完整异常由日志追踪。 */
    private String summary(RuntimeException exception) {
        String message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return message.substring(0, Math.min(message.length(), 256));
    }
}
