package com.byy.ticket.payment.mq;

import com.byy.ticket.payment.model.OutboxEvent;
import com.byy.ticket.observability.TraceSupport;
import io.micrometer.tracing.Span;
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
    private final TraceSupport traces;
    public PaymentEventPublisher(RabbitTemplate rabbit, @Value("${ticket.mq.prefix:ticket}") String prefix, TraceSupport traces) {
        this.rabbit=rabbit; this.exchange=prefix+".payment.exchange";
        this.traces = traces;
    }
    public void publish(OutboxEvent event) throws Exception {
        // 一个事件的每次发送都有独立 Span，恢复原付款请求而非调度线程的上下文。
        try (var scope = traces.resume(new TraceSupport.Snapshot(event.getTraceParent(), event.getTraceState()),
                "payment.succeeded publish", Span.Kind.PRODUCER)) {
            scope.tag("messaging.system", "rabbitmq");
            scope.tag("messaging.destination.name", exchange);
            try { send(event); }
            catch (Exception failure) { scope.error(failure); throw failure; }
        }
    }
    private void send(OutboxEvent event) throws Exception {
        var properties=new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setMessageId(event.getEventId());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("X-Trace-Id",com.byy.ticket.common.trace.TraceIdContext.getOrCreate());
        traces.inject(properties.getHeaders());
        var correlation=new CorrelationData(event.getEventId());
        rabbit.send(exchange,"payment.succeeded",
                new Message(event.getPayload().getBytes(StandardCharsets.UTF_8),properties),correlation);
        var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
        if (!confirm.ack() || correlation.getReturned()!=null)
            throw new IllegalStateException("支付事件发布未确认或无法路由");
    }
}
