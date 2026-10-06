package com.byy.ticket.order.mq;

/** Order独立维护收到的消息契约，不依赖Payment实现类。 */
public record PaymentSucceededMessage(String eventId, String eventType, int schemaVersion,
                                      String orderNo, String paymentNo) {
    public void validate() {
        if (!valid(eventId) || !valid(orderNo) || !valid(paymentNo)
                || !"PAYMENT_SUCCEEDED".equals(eventType) || schemaVersion!=1)
            throw new IllegalArgumentException("支付消息身份或契约不正确");
    }
    private boolean valid(String value) { return value!=null && value.matches("[0-9a-f]{32}"); }
}
