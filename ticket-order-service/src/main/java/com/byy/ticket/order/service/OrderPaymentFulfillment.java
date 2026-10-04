package com.byy.ticket.order.service;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.order.client.InventoryClient;
import com.byy.ticket.order.client.PaymentClient;
import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.client.exception.PaymentServiceCallException;
import com.byy.ticket.order.mapper.*;
import com.byy.ticket.order.model.*;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 付款后的库存确认与冲正步骤；每次 HTTP 都在本地事务外，结果由令牌条件 SQL 保存。 */
@Service
public class OrderPaymentFulfillment {
    private static final String REVERSAL_REASON = "库存已释放，订单无法履约";
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final InventoryClient inventory;
    private final PaymentClient payments;
    private final OrderPaymentReceiptTransaction receipts;
    private final Clock clock;

    /** 注入订单持久化和两个远程客户端，不直接读取库存或支付数据库。 */
    public OrderPaymentFulfillment(OrderMapper orders, OrderItemMapper items, InventoryClient inventory,
            PaymentClient payments, OrderPaymentReceiptTransaction receipts, Clock clock) {
        this.orders=orders; this.items=items; this.inventory=inventory;
        this.payments=payments; this.receipts=receipts; this.clock=clock;
    }

    /** 到期关闭前回查支付；已付款则保存依据转入库存核对，未创建/未付款才继续释放。 */
    public boolean recordPaymentBeforeClose(TicketOrder order) {
        PaymentResponse payment;
        try { payment = payments.getByOrder(order.getOrderNo()); }
        catch (ResourceNotFoundException missing) { return false; }
        requireOrderSnapshot(order, payment);
        if (!"SUCCESS".equals(payment.status())) { return false; }
        receipts.accept(payment);
        return true;
    }

    /** 核实付款和原预留；SOLD 才成交，RELEASED 才安排冲正，不重新占用其他用户的库存。 */
    public void confirm(TicketOrder order, String token) {
        var payment = requirePaymentEvidence(order);
        var stock = inventory.getReservation(order.getReservationId());
        requireReservation(order, stock);
        if (payment.reversalNo() != null) {
            if ("SOLD".equals(stock.status())) {
                review(order, token, "库存已售出但支付已冲正，需人工核对");
                return;
            }
            if ("RESERVED".equals(stock.status())) {
                stock = inventory.release(order.getReservationId());
                requireReservation(order, stock);
            }
            scheduleReversal(order, token);
            return;
        }
        if ("RELEASED".equals(stock.status())) {
            scheduleReversal(order, token);
            return;
        }
        if ("RESERVED".equals(stock.status())) {
            stock = inventory.confirm(order.getReservationId());
            requireReservation(order, stock);
        }
        if (!"SOLD".equals(stock.status())) { throw new IllegalArgumentException("库存尚未确认售出"); }
        // 避免独立模拟冲正发生在第一次支付查询之后时误报成交。
        var latest = requirePaymentEvidence(order);
        if (latest.reversalNo() != null) {
            review(order, token, "确认售出期间支付发生外部冲正，需人工核对");
            return;
        }
        orders.finish(order.getId(), token, order.getStatus(), OrderStatus.PAID.name(),
                order.getReservationId(), now(), null);
    }

    /** 只对已释放库存冲正；成功响应丢失时，先查询原支付事实即可恢复同一冲正结果。 */
    public void reverse(TicketOrder order, String token) {
        var stock = inventory.getReservation(order.getReservationId());
        requireReservation(order, stock);
        if (!"RELEASED".equals(stock.status())) {
            review(order, token, "冲正前库存并非已释放，停止自动退款");
            return;
        }
        var payment = requirePaymentEvidence(order);
        if ("SUCCESS".equals(payment.reversalStatus())) {
            orders.finishReversal(order.getId(), token, payment.reversalNo());
            return;
        }
        try {
            var reversal = payments.reverse(new PaymentReversalRequest(order.getPaymentNo(), order.getReversalReason()),
                    order.getOrderNo(), order.getTotalAmount());
            orders.finishReversal(order.getId(), token, reversal.reversalNo());
        } catch (PaymentServiceCallException exception) {
            if (exception.getReason() != PaymentServiceCallException.Reason.CONFLICT) { throw exception; }
            review(order, token, "冲正状态或首次原因冲突，需人工核对");
        }
    }

    /** 原订单用户、金额、期限必须与支付库快照一致，不能仅比较金额或成功标记。 */
    private void requireOrderSnapshot(TicketOrder order, PaymentResponse payment) {
        if (!order.getOrderNo().equals(payment.orderNo()) || !order.getUserId().equals(payment.userId())
                || order.getTotalAmount().compareTo(payment.amount()) != 0
                || !order.getExpiresAt().equals(payment.expiresAt())) {
            throw new IllegalArgumentException("付款与订单快照不一致");
        }
    }

    /** 当前远程事实必须匹配订单已提交的首次付款依据。 */
    private PaymentResponse requirePaymentEvidence(TicketOrder order) {
        var payment = payments.getByOrder(order.getOrderNo());
        requireOrderSnapshot(order, payment);
        if (!"SUCCESS".equals(payment.status()) || order.getPaymentNo() == null || order.getPaidAt() == null
                || !order.getPaymentNo().equals(payment.paymentNo()) || !order.getPaidAt().equals(payment.paidAt())
                || !payment.paidAt().isBefore(order.getExpiresAt())) {
            throw new IllegalArgumentException("订单缺少一致的有效付款依据");
        }
        return payment;
    }

    /** 对所有查询/确认/释放结果核对原订单、场次、票档、数量和期限，防止错误预留被成交或冲正。 */
    private void requireReservation(TicketOrder order, StockReservationResponse stock) {
        var item = items.selectByOrderId(order.getId());
        if (item == null || !order.getReservationId().equals(stock.reservationId())
                || !order.getOrderNo().equals(stock.orderId()) || !item.getSessionId().equals(stock.sessionId())
                || !item.getTicketTierId().equals(stock.ticketTierId()) || !item.getQuantity().equals(stock.quantity())
                || !order.getExpiresAt().equals(stock.expiresAt())) {
            throw new IllegalArgumentException("库存预留与原订单快照不一致");
        }
    }

    /** 冲正步骤必须先落库；下一次领取再发送 HTTP，重启和重复通知不会改变首次原因。 */
    private void scheduleReversal(TicketOrder order, String token) {
        orders.scheduleReversal(order.getId(), token, REVERSAL_REASON);
    }

    /** 矛盾事实停止自动操作，保留原支付与预留依据，供人工检查。 */
    private void review(TicketOrder order, String token, String error) {
        orders.finish(order.getId(), token, order.getStatus(), OrderStatus.REVIEW_REQUIRED.name(),
                order.getReservationId(), now(), error);
    }

    /** 毫秒精度东八区时间，与数据库一致。 */
    private LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS); }
}
