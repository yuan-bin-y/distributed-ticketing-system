package com.byy.ticket.order.service;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.order.client.dto.PaymentResponse;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.mapper.OrderMapper;
import com.byy.ticket.order.model.OrderStatus;
import com.byy.ticket.order.vo.order.PaymentReceiptVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 独立本地事务：锁定订单、核对快照、幂等保存付款依据，然后才能确认通知已接收。 */
@Service
public class OrderPaymentReceiptTransaction {
    private final OrderMapper orders;

    /** 注入订单自己的 Mapper。 */
    public OrderPaymentReceiptTransaction(OrderMapper orders) { this.orders = orders; }

    /** 与超时关闭竞争；有原预留编号的付款进入库存核对，重复通知不重置后台进度。 */
    @Transactional
    public PaymentReceiptVO accept(PaymentResponse payment) {
        var order = orders.selectByNoForUpdate(payment.orderNo());
        if (order == null) { throw new ResourceNotFoundException("订单不存在"); }
        if (!order.getUserId().equals(payment.userId())
                || order.getTotalAmount().compareTo(payment.amount()) != 0
                || !order.getExpiresAt().equals(payment.expiresAt())
                || !"SUCCESS".equals(payment.status()) || payment.paidAt() == null
                || !payment.paidAt().isBefore(order.getExpiresAt())) {
            throw new OrderConflictException("支付事实与订单快照不一致");
        }
        if (order.getPaymentNo() != null && (!order.getPaymentNo().equals(payment.paymentNo())
                || !order.getPaidAt().equals(payment.paidAt()))) {
            throw new OrderConflictException("订单已记录不同付款依据");
        }
        boolean reversed = payment.reversalNo() != null;
        boolean fulfilled=OrderStatus.PAID.name().equals(order.getStatus())
                || OrderStatus.COMPLETED.name().equals(order.getStatus());
        if (order.getPaymentNo() != null && (!reversed || !fulfilled)) {
            return new PaymentReceiptVO(order.getOrderNo(), order.getPaymentNo(), true);
        }
        boolean canReconcile = order.getReservationId() != null
                && !fulfilled;
        String target = canReconcile ? OrderStatus.PAYMENT_CONFIRMING.name() : OrderStatus.REVIEW_REQUIRED.name();
        String error = canReconcile ? null : "付款缺少预留依据或已成交后出现外部冲正，需人工核对";
        if (orders.savePayment(order.getId(), payment.paymentNo(), payment.paidAt(), target, error) != 1) {
            throw new IllegalStateException("付款依据保存失败");
        }
        return new PaymentReceiptVO(order.getOrderNo(), payment.paymentNo(), true);
    }
}
