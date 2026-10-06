package com.byy.ticket.payment.mq;

import com.byy.ticket.payment.model.OutboxEvent;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 发送原载荷并等待Confirm；Return或超时都不能当作发布成功。 */
@Component
@ConditionalOnProperty(name="ticket.mq.enabled", havingValue="true")
public class PaymentEventPublisher {
    private final RabbitTemplate rabbit;
    private final String exchange;
    public PaymentEventPublisher(RabbitTemplate rabbit, @Value("${ticket.mq.prefix:ticket}") String prefix) {
        this.rabbit=rabbit; this.exchange=prefix+".payment.exchange";
    }
    public void publish(OutboxEvent event) throws Exception {
        var properties=new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setMessageId(event.getEventId());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("X-Trace-Id",event.getTraceId());
        var correlation=new CorrelationData(event.getEventId());
        rabbit.send(exchange,"payment.succeeded",
                new Message(event.getPayload().getBytes(StandardCharsets.UTF_8),properties),correlation);
        var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
        if (!confirm.ack() || correlation.getReturned()!=null)
            throw new IllegalStateException("支付事件发布未确认或无法路由");
    }
}
