package com.byy.ticket.order.mq;

import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.observability.TraceSupport;
import io.micrometer.tracing.Span;
import com.byy.ticket.order.client.PaymentClient;
import com.byy.ticket.order.client.exception.PaymentServiceCallException;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** 回查事实在事务外，接收提交后ACK；失败转发确认后才ACK原消息。 */
@Component
@ConditionalOnProperty(name="ticket.mq.enabled", havingValue="true")
public class PaymentMessageConsumer {
    private final ObjectMapper json;
    private final PaymentClient payments;
    private final PaymentMessageTransaction transaction;
    private final RabbitTemplate rabbit;
    private final String exchange;
    private final TraceSupport traces;
    public PaymentMessageConsumer(ObjectMapper json,PaymentClient payments,PaymentMessageTransaction transaction,
                                  RabbitTemplate rabbit,@Value("${ticket.mq.prefix:ticket}") String prefix, TraceSupport traces) {
        this.json=json;this.payments=payments;this.transaction=transaction;
        this.rabbit=rabbit;this.exchange=prefix+".payment.exchange";
        this.traces=traces;
    }
    @RabbitListener(queues="${ticket.mq.prefix:ticket}.order.payment-succeeded",ackMode="MANUAL")
    public void consume(Message message,Channel channel) throws Exception {
        long tag=message.getMessageProperties().getDeliveryTag();
        try (var scope=traces.resume(TraceSupport.Snapshot.from(message.getMessageProperties().getHeaders()),
                "payment.succeeded consume", Span.Kind.CONSUMER)) {
            scope.tag("messaging.system", "rabbitmq");
            scope.tag("messaging.destination.name", message.getMessageProperties().getConsumerQueue());
            try {
                if(message.getBody().length>16384) throw new IllegalArgumentException("支付消息过大");
                PaymentSucceededMessage event;
                try { event=json.readValue(message.getBody(),PaymentSucceededMessage.class); }
                catch(RuntimeException invalid) { throw new IllegalArgumentException("支付消息JSON无效",invalid); }
                if(event==null) throw new IllegalArgumentException("支付消息为空");
                event.validate();
                if(!event.eventId().equals(message.getMessageProperties().getMessageId()))
                    throw new IllegalArgumentException("消息头与载荷编号不一致");
                // 按契约字段计算指纹，JSON空白或字段顺序变化不影响幂等。
                String canonical=event.eventId()+"|"+event.eventType()+"|"+event.schemaVersion()+"|"
                        +event.orderNo()+"|"+event.paymentNo();
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8)));
                var fact=payments.getByOrder(event.orderNo());
                transaction.accept(event,hash,fact);
            } catch(Exception failure) {
                scope.error(failure);
                try {
                    forwardFailure(message,failure);
                } catch(Exception forwardingFailure) {
                    // MANUAL模式显式保留原投递；重试目标不可用时不能删除原消息。
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("支付消息转发未确认，保留原消息",
                            forwardingFailure);
                    Thread.sleep(500);
                    channel.basicNack(tag,false,true);
                    return;
                }
            }
            try { channel.basicAck(tag,false); }
            catch (Exception failure) { scope.error(failure); throw failure; }
        }
    }
    /** 三档临时重试后进入死信；转发失败抛出，由容器保留并重新投递原消息。 */
    private void forwardFailure(Message original,Exception failure) throws Exception {
        // 重试和死信转发建立 Producer 子 Span；下次投递成为新的 Consumer 子 Span。
        try (var scope=traces.resume(traces.capture(), "payment.succeeded retry publish", Span.Kind.PRODUCER)) {
            scope.tag("messaging.system", "rabbitmq");
            try { sendFailure(original, failure); }
            catch (Exception problem) { scope.error(problem); throw problem; }
        }
    }
    private void sendFailure(Message original,Exception failure) throws Exception {
        Object value=original.getMessageProperties().getHeaders().get("x-ticket-retry");
        int retry=value instanceof Number ? ((Number)value).intValue() : 0;
        if(retry<0 || retry>3) retry=3;
        boolean permanent=failure instanceof IllegalArgumentException || failure instanceof OrderConflictException
                || failure instanceof ResourceNotFoundException;
        if(failure instanceof PaymentServiceCallException call)
            permanent=call.getReason()==PaymentServiceCallException.Reason.INVALID_RESPONSE
                    || call.getReason()==PaymentServiceCallException.Reason.CONFLICT;
        String routing=permanent || retry>=3 ? "payment.dead" : "payment.retry."+(retry+1);
        // 新消息不继承投递标签或旧x-death；业务身份和原载荷始终保留。
        var properties=new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(original.getMessageProperties().getMessageId());
        properties.setHeader("X-Trace-Id",TraceIdContext.getOrCreate());
        traces.inject(properties.getHeaders());
        properties.setHeader("x-ticket-retry",retry+1);
        properties.setHeader("x-ticket-error",failure.getClass().getSimpleName());
        var correlation=new CorrelationData(java.util.UUID.randomUUID().toString());
        rabbit.send(exchange,routing,new Message(original.getBody(),properties),correlation);
        var confirm=correlation.getFuture().get(5,TimeUnit.SECONDS);
        if(!confirm.ack() || correlation.getReturned()!=null)
            throw new IllegalStateException("支付消费重试或死信转发未确认",failure);
    }
}
