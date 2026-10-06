package com.byy.ticket.payment.mq.event;

/** 支付通知线索；Order仍需回查Payment事实，消息不直接提供出票依据。 */
public record PaymentSucceededEvent(String eventId, String eventType, int schemaVersion,
                                    String orderNo, String paymentNo) {
    public static final String TYPE = "PAYMENT_SUCCEEDED";
    public static final int VERSION = 1;
}
