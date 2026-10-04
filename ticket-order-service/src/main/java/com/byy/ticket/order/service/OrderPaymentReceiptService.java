package com.byy.ticket.order.service;

import com.byy.ticket.order.client.PaymentClient;
import com.byy.ticket.order.dto.order.PaymentResultDTO;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.vo.order.PaymentReceiptVO;
import org.springframework.stereotype.Service;

/** 先通过 HTTP 核实支付事实，再进入本地事务；数据库锁不覆盖网络等待。 */
@Service
public class OrderPaymentReceiptService {
    private final PaymentClient payments;
    private final OrderPaymentReceiptTransaction transactions;

    /** 注入远程查询与独立事务 Bean，避免自身调用绕过事务代理。 */
    public OrderPaymentReceiptService(PaymentClient payments, OrderPaymentReceiptTransaction transactions) {
        this.payments = payments;
        this.transactions = transactions;
    }

    /** 每次通知都核实来源事实；重复通知返回同一付款依据的接收确认。 */
    public PaymentReceiptVO receive(PaymentResultDTO notification) {
        if (notification == null || notification.orderNo() == null || notification.paymentNo() == null
                || !notification.orderNo().matches("[0-9a-f]{32}")
                || !notification.paymentNo().matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("付款通知编号不正确");
        }
        var payment = payments.getByOrder(notification.orderNo());
        if (!notification.paymentNo().equals(payment.paymentNo())) {
            throw new OrderConflictException("通知支付编号与支付服务记录不一致");
        }
        return transactions.accept(payment);
    }
}
