package com.byy.ticket.order.vo.order;

/** accepted 仅表示订单已保存付款依据，不表示库存已售出或订单已成交。 */
public record PaymentReceiptVO(String orderNo, String paymentNo, boolean accepted) { }
