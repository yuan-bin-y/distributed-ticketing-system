package com.byy.ticket.payment.mq;

import com.byy.ticket.payment.model.OutboxEvent;
import com.byy.ticket.payment.mq.event.PaymentSucceededEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 在发布前校验持久化契约，损坏载荷不能反复发送到Broker。 */
@Component
public class OutboxMessageValidator {
    private final ObjectMapper json;
    public OutboxMessageValidator(ObjectMapper json) { this.json = json; }

    public void validate(OutboxEvent event) {
        try {
            var message = json.readValue(event.getPayload(), PaymentSucceededEvent.class);
            if (message == null || !validNo(message.eventId()) || !validNo(message.orderNo())
                    || !validNo(message.paymentNo()) || !message.eventId().equals(event.getEventId())
                    || !message.paymentNo().equals(event.getAggregateId())
                    || !PaymentSucceededEvent.TYPE.equals(event.getEventType())
                    || !PaymentSucceededEvent.TYPE.equals(message.eventType())
                    || message.schemaVersion() != PaymentSucceededEvent.VERSION)
                throw new IllegalArgumentException("Outbox消息契约不一致");
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalArgumentException("Outbox消息JSON无效", invalid);
        }
    }
    private boolean validNo(String value) { return value != null && value.matches("[a-f0-9]{32}"); }
}
