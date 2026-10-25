package com.byy.ticket.order.review;

import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.model.*;
import java.time.LocalDateTime;

/** 无网络副作用的人工处置规则。管理员选择意图，服务端核实是否满足执行条件。 */
public final class OrderReviewDecision {
    public enum Intent { RECHECK, RECONCILE, RESUME, CLOSE, REVERSE }
    public record Facts(PaymentResponse payment, StockReservationResponse stock) {}
    private OrderReviewDecision() {}

    public static OrderStatus decide(Intent intent, TicketOrder order, OrderItem item, Facts facts, LocalDateTime now) {
        if (item == null) throw conflict("原订单快照缺失，不能自动修复");
        var payment = facts.payment();
        var stock = facts.stock();
        if (stock == null && order.getReservationId() != null)
            throw conflict("原预留编号已存在但库存查询缺失，不能推断已释放");
        if (payment != null && (!order.getOrderNo().equals(payment.orderNo())
                || !order.getUserId().equals(payment.userId())
                || order.getTotalAmount().compareTo(payment.amount()) != 0
                || !order.getExpiresAt().equals(payment.expiresAt())
                || (order.getPaymentNo() != null && (!order.getPaymentNo().equals(payment.paymentNo())
                    || !java.util.Objects.equals(order.getPaidAt(), payment.paidAt())))))
            throw conflict("付款事实与原订单不一致");
        if (stock != null && (!order.getOrderNo().equals(stock.orderId())
                || !item.getTicketTierId().equals(stock.ticketTierId()) || !item.getSessionId().equals(stock.sessionId())
                || !item.getQuantity().equals(stock.quantity()) || !order.getExpiresAt().equals(stock.expiresAt())
                || (order.getReservationId() != null && !order.getReservationId().equals(stock.reservationId()))))
            throw conflict("库存事实与原订单不一致");
        if (intent == Intent.RECHECK) return OrderStatus.REVIEW_REQUIRED;
        boolean paid = payment != null && "SUCCESS".equals(payment.status());
        if (paid && (payment.paidAt() == null || !payment.paidAt().isBefore(order.getExpiresAt())))
            throw conflict("付款时间不符合原订单期限");
        if (!paid && order.getPaymentNo() != null) throw conflict("原付款依据与当前查询结果矛盾");
        if (paid) {
            if (stock == null) throw conflict("有付款但缺少原库存预留，继续核对");
            boolean reversal = "RELEASED".equals(stock.status()) || payment.reversalNo() != null;
            if (payment.reversalNo() != null && "SOLD".equals(stock.status()))
                throw conflict("库存已售出但支付已冲正，继续人工核对");
            if (intent == Intent.CLOSE || (intent == Intent.RESUME && reversal)
                    || (intent == Intent.REVERSE && !reversal)) throw conflict("所选处理方向与付款、库存事实不符");
            if (!reversal && !"HELD".equals(order.getQuotaStatus())) throw conflict("购买额度已归还，不能直接恢复履约");
            return OrderStatus.PAYMENT_CONFIRMING;
        }
        if (intent == Intent.REVERSE) throw conflict("未核实有效付款，不能冲正");
        if (stock != null && "SOLD".equals(stock.status())) throw conflict("库存已售出但没有一致的付款事实");
        if (order.getExpiresAt().isAfter(now)) {
            if (!"HELD".equals(order.getQuotaStatus())) throw conflict("购买额度已归还，不能恢复待支付");
            if (intent != Intent.CLOSE && stock != null && "RESERVED".equals(stock.status()))
                return OrderStatus.PENDING_PAYMENT;
            throw conflict("未到期订单不允许按未付款到期关闭");
        }
        if (intent == Intent.RESUME) throw conflict("未付款订单已到期，应核对关闭");
        return stock == null ? OrderStatus.CLOSED : OrderStatus.CLOSING;
    }

    private static OrderConflictException conflict(String message) { return new OrderConflictException(message); }
}
