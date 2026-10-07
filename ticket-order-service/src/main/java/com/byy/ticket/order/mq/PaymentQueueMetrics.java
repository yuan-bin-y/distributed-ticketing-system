package com.byy.ticket.order.mq;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

/** 被动读取已声明队列深度；不消费或修改消息。多Order副本读同一队列，面板使用max聚合。 */
@Component
@ConditionalOnProperty(name="ticket.mq.enabled",havingValue="true")
public class PaymentQueueMetrics {
    private final RabbitTemplate rabbit;
    private final MeterRegistry metrics;
    private final List<QueueSample> queues;
    private volatile double healthy;
    public PaymentQueueMetrics(RabbitTemplate rabbit,MeterRegistry metrics,
                               @Value("${ticket.mq.prefix:ticket}") String prefix) {
        this.rabbit=rabbit;this.metrics=metrics;
        queues=List.of(new QueueSample("payment",prefix+".order.payment-succeeded"),
                new QueueSample("retry1",prefix+".order.payment-retry-1"),
                new QueueSample("retry2",prefix+".order.payment-retry-2"),
                new QueueSample("retry3",prefix+".order.payment-retry-3"),
                new QueueSample("dead",prefix+".order.payment-dead"));
        for(var queue:queues) Gauge.builder("ticket.mq.queue.messages",queue,q->q.messages)
                .tag("queue",queue.label).register(metrics);
        Gauge.builder("ticket.mq.collector.healthy",this,c->c.healthy).register(metrics);
    }
    @Scheduled(fixedDelay=10000)
    public void sample() {
        try {
            for(var queue:queues) {
                Integer count=rabbit.execute(channel->channel.queueDeclarePassive(queue.name).getMessageCount());
                queue.messages=count==null?Double.NaN:count;
            }
            healthy=1;
        } catch(Exception failure) {
            healthy=0;metrics.counter("ticket.mq.collector.errors").increment();
        }
    }
    private static class QueueSample {
        final String label,name;
        volatile double messages=Double.NaN;
        QueueSample(String label,String name){this.label=label;this.name=name;}
    }
}
