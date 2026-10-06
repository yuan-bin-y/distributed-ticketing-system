package com.byy.ticket.order.mq;

import com.byy.ticket.order.client.dto.PaymentResponse;
import com.byy.ticket.order.service.OrderPaymentReceiptTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 消费幂等记录和订单付款依据一起提交；任何一步失败都不留下已消费标记。 */
@Service
public class PaymentMessageTransaction {
    private final ConsumedEventMapper consumed;
    private final OrderPaymentReceiptTransaction receipts;
    public PaymentMessageTransaction(ConsumedEventMapper consumed,OrderPaymentReceiptTransaction receipts) {
        this.consumed=consumed;this.receipts=receipts;
    }
    @Transactional
    public void accept(PaymentSucceededMessage event,String hash,PaymentResponse fact) {
        if(!event.paymentNo().equals(fact.paymentNo()) || !event.orderNo().equals(fact.orderNo()))
            throw new IllegalArgumentException("支付消息与付款事实归属不一致");
        consumed.insertOrKeep(event,hash);
        if(!hash.equals(consumed.hash(event.eventId())))
            throw new IllegalArgumentException("同一事件编号收到不同消息内容");
        receipts.accept(fact);
    }
}
