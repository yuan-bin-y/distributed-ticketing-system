package com.byy.ticket.order.service;

import com.byy.ticket.order.client.dto.TicketPurchaseRuleResponse;
import com.byy.ticket.common.trace.PerformanceSpan;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.mapper.OrderMapper;
import com.byy.ticket.order.mapper.OrderItemMapper;
import com.byy.ticket.order.mapper.PurchaseQuotaMapper;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.model.TicketOrder;
import com.byy.ticket.order.model.OrderItem;
import com.byy.ticket.order.model.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** 订单本地事务边界；单独的 Spring Bean 保证调用经事务代理，内部不发送网络请求。 */
@Service
public class OrderTransactionService {
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final PurchaseQuotaMapper quotas;

    /** 注入属于订单库的订单、快照和购买额度Mapper。 */
    public OrderTransactionService(OrderMapper orders, OrderItemMapper items, PurchaseQuotaMapper quotas) {
        this.orders = orders;
        this.items = items;
        this.quotas = quotas;
    }

    /** 原子保存订单、快照及累计额度；超过限购全部回滚，重复幂等键不再占额度。 */
    @Transactional
    public TicketOrder create(Long userId, OrderCreateDTO request, TicketPurchaseRuleResponse rule,
                              BigDecimal amount, LocalDateTime now, LocalDateTime expiresAt) {
        TicketOrder order = new TicketOrder();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setIdempotencyKey(request.idempotencyKey());
        order.setTotalAmount(amount);
        order.setStatus(OrderStatus.STOCK_PENDING.name());
        order.setQuotaStatus("HELD");
        order.setExpiresAt(expiresAt);
        order.setNextAttemptAt(now);
        order.setAttemptCount(0);
        PerformanceSpan.measure("sql.order.insert",()->orders.insert(order));
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setEventId(rule.eventId());
        item.setSessionId(rule.sessionId());
        item.setTicketTierId(rule.ticketTierId());
        item.setTicketTierName(rule.ticketTierName());
        item.setUnitPrice(rule.price());
        item.setQuantity(request.quantity());
        item.setSubtotalAmount(amount);
        PerformanceSpan.measure("sql.item.insert",()->items.insert(item));
        PerformanceSpan.measure("sql.quota.ensure",()->quotas.ensureExists(userId, rule.sessionId()));
        if (PerformanceSpan.measure("sql.quota.occupy",()->quotas.occupy(userId, rule.sessionId(), request.quantity(), rule.purchaseLimit())) != 1) {
            throw new OrderConflictException("该场次累计购买数量超过限购");
        }
        return order;
    }

    /** 已明确创建失败或库存已释放才调用；终态、额度减法及归还标记一起提交。 */
    @Transactional
    public int finishAndReleaseQuota(Long id, String token, String expected, OrderStatus target,
                                     String reservationId, LocalDateTime now, String error) {
        if (target != OrderStatus.CREATE_FAILED && target != OrderStatus.CLOSED) {
            throw new IllegalArgumentException("当前终态不能归还购买额度");
        }
        TicketOrder order = orders.selectByIdForUpdate(id);
        if (!matchesTask(order, token, expected)) { return 0; }
        int changed = orders.finish(id, token, expected, target.name(), reservationId, now, error);
        if (changed != 1) { return 0; }
        releaseQuota(order);
        return 1;
    }

    /** HTTP已核实库存RELEASED及支付冲正成功；重复任务或迟到通知不重复归还。 */
    @Transactional
    public int finishReversalAndReleaseQuota(Long id, String token, String reversalNo) {
        TicketOrder order = orders.selectByIdForUpdate(id);
        if (!matchesTask(order, token, OrderStatus.REVERSAL_PENDING.name())) { return 0; }
        if (orders.finishReversal(id, token, reversalNo) != 1) { return 0; }
        releaseQuota(order);
        return 1;
    }

    /** 锁定后重新核对领取令牌和前置状态，旧工作线程不能释放新任务的额度。 */
    private boolean matchesTask(TicketOrder order, String token, String expected) {
        return order != null && token != null && token.equals(order.getLeaseToken())
                && expected.equals(order.getStatus());
    }

    /** 订单行锁和归还标记保证一次；账目异常回滚终态，保留原任务继续核对。 */
    private void releaseQuota(TicketOrder order) {
        if ("RELEASED".equals(order.getQuotaStatus())) { return; }
        if (!"HELD".equals(order.getQuotaStatus())) { throw new IllegalStateException("订单额度状态异常"); }
        OrderItem item = items.selectByOrderId(order.getId());
        if (item == null || quotas.release(order.getUserId(), item.getSessionId(), item.getQuantity()) != 1
                || orders.markQuotaReleased(order.getId()) != 1) {
            throw new IllegalStateException("购买额度记录不一致，停止终态提交");
        }
    }
}
