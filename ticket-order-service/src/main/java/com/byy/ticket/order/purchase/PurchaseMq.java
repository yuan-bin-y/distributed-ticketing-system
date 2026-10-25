package com.byy.ticket.order.purchase;

import com.rabbitmq.client.Channel;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;

/** 异步下单使用独立交换机和队列；发送确认与消费事务的边界分开处理。 */
@Configuration
@ConditionalOnProperty(name = "ticket.mq.enabled", havingValue = "true")
public class PurchaseMq {
    public record Requested(String eventId, String requestId, int version) {}

    @Bean
    Declarables purchaseTopology(@Value("${ticket.mq.prefix:ticket}") String prefix) {
        var exchange = new DirectExchange(prefix + ".purchase.exchange");
        var queue =
                QueueBuilder.durable(prefix + ".order.purchase-requested")
                        .quorum()
                        .withArgument("x-delivery-limit", -1)
                        .build();
        var dead = QueueBuilder.durable(prefix + ".order.purchase-dead").quorum().build();
        return new Declarables(
                exchange,
                queue,
                dead,
                BindingBuilder.bind(queue).to(exchange).with("purchase.requested"),
                BindingBuilder.bind(dead).to(exchange).with("purchase.dead"));
    }

    @Component
    @ConditionalOnProperty(name = "ticket.mq.enabled", havingValue = "true")
    public static class Relay {
        private final PurchaseOutboxMapper outbox;
        private final RabbitTemplate rabbit;
        private final ObjectMapper json;
        private final String exchange;
        private final MeterRegistry metrics;
        private final com.byy.ticket.observability.TraceSupport traces;
        private final PurchaseAcceptanceMapper acceptances;

        public Relay(
                PurchaseOutboxMapper outbox,
                RabbitTemplate rabbit,
                ObjectMapper json,
                @Value("${ticket.mq.prefix:ticket}") String prefix,
                MeterRegistry metrics,
                com.byy.ticket.observability.TraceSupport traces,
                PurchaseAcceptanceMapper acceptances) {
            this.outbox = outbox;
            this.rabbit = rabbit;
            this.json = json;
            this.exchange = prefix + ".purchase.exchange";
            this.metrics = metrics;
            this.traces = traces;
            this.acceptances = acceptances;
        }

        @Scheduled(
                scheduler = "purchaseRelayScheduler",
                fixedDelayString = "${ticket.purchase.outbox-ms:1000}")
        public void publishDue() {
            for (String id : outbox.due()) publish(id);
        }

        public void publish(String id) {
            String token = UUID.randomUUID().toString().replace("-", "");
            if (outbox.claim(id, token) != 1) return;
            var event = outbox.get(id);
            var accepted = acceptances.selectById(event.requestId());
            var scope =
                    traces.resume(
                            new com.byy.ticket.observability.TraceSupport.Snapshot(
                                    accepted == null ? null : accepted.getTraceParent(),
                                    accepted == null ? null : accepted.getTraceState()),
                            "order.purchase publish",
                            io.micrometer.tracing.Span.Kind.PRODUCER);
            try (scope) {
                var properties = new MessageProperties();
                properties.setMessageId(id);
                properties.setContentType("application/json");
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                traces.inject(properties.getHeaders());
                confirmed(
                        rabbit,
                        exchange,
                        "purchase.requested",
                        new Message(
                                json.writeValueAsBytes(new Requested(id, event.requestId(), 1)),
                                properties));
                outbox.finish(id, token, "PUBLISHED", null, 0);
                metrics.counter("ticket.purchase.outbox", "outcome", "published").increment();
            } catch (Exception failure) {
                boolean review = event.attemptCount() >= 20;
                outbox.finish(
                        id,
                        token,
                        review ? "REVIEW_REQUIRED" : "PENDING",
                        failure.getClass().getSimpleName(),
                        Math.min(300, 1 << Math.min(event.attemptCount(), 8)));
                metrics.counter("ticket.purchase.outbox", "outcome", review ? "review" : "retry")
                        .increment();
            }
        }
    }

    @Component
    @ConditionalOnProperty(name = "ticket.mq.enabled", havingValue = "true")
    public static class Consumer {
        private final PurchaseOutboxMapper outbox;
        private final AsyncPurchaseWorker worker;
        private final ObjectMapper json;
        private final RabbitTemplate rabbit;
        private final String exchange;
        private final java.util.concurrent.atomic.AtomicInteger failures =
                new java.util.concurrent.atomic.AtomicInteger();

        public Consumer(
                PurchaseOutboxMapper outbox,
                AsyncPurchaseWorker worker,
                ObjectMapper json,
                RabbitTemplate rabbit,
                @Value("${ticket.mq.prefix:ticket}") String prefix) {
            this.outbox = outbox;
            this.worker = worker;
            this.json = json;
            this.rabbit = rabbit;
            this.exchange = prefix + ".purchase.exchange";
        }

        @RabbitListener(queues = "${ticket.mq.prefix:ticket}.order.purchase-requested")
        public void consume(Message message, Channel channel) throws Exception {
            long tag = message.getMessageProperties().getDeliveryTag();
            try {
                Requested event;
                try {
                    if (message.getBody().length > 4096) throw new IllegalArgumentException("消息过大");
                    event = json.readValue(message.getBody(), Requested.class);
                    if (event.version() != 1
                            || event.eventId() == null
                            || !event.eventId().matches("[a-f0-9]{32}")
                            || event.requestId() == null
                            || !event.requestId().matches("[a-f0-9]{32}")
                            || !event.eventId()
                                    .equals(message.getMessageProperties().getMessageId()))
                        throw new IllegalArgumentException("消息字段无效");
                } catch (RuntimeException invalid) {
                    confirmed(rabbit, exchange, "purchase.dead", message);
                    channel.basicAck(tag, false);
                    failures.set(0);
                    return;
                }
                var stored = outbox.get(event.eventId());
                if (stored == null || !event.requestId().equals(stored.requestId())) {
                    confirmed(rabbit, exchange, "purchase.dead", message);
                    channel.basicAck(tag, false);
                    failures.set(0);
                    return;
                }
                worker.advance(event.requestId());
                channel.basicAck(tag, false);
                failures.set(0);
            } catch (Exception uncertain) {
                int count = failures.incrementAndGet();
                long delay = Math.min(30000, 500L << Math.min(count - 1, 6));
                Thread.sleep(delay + ThreadLocalRandom.current().nextLong(100));
                channel.basicNack(tag, false, true);
            }
        }
    }

    private static void confirmed(
            RabbitTemplate rabbit, String exchange, String routing, Message message)
            throws Exception {
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(exchange, routing, message, correlation);
        if (!correlation.getFuture().get(5, TimeUnit.SECONDS).ack()
                || correlation.getReturned() != null) throw new IllegalStateException("消息未确认或无法路由");
    }
}
