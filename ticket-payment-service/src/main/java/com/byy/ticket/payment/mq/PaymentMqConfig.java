package com.byy.ticket.payment.mq;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;

/** 两端声明相同持久化拓扑；重试队列使用quorum的至少一次死信转发。 */
@Configuration
@ConditionalOnProperty(name="ticket.mq.enabled", havingValue="true")
public class PaymentMqConfig {
    @Bean
    public Declarables paymentTopology(@Value("${ticket.mq.prefix:ticket}") String prefix,
            @Value("${ticket.mq.retry-delays-ms:10000,30000,120000}") String delays) {
        java.util.List<Declarable> definitions = new java.util.ArrayList<>();
        var exchange = new DirectExchange(prefix + ".payment.exchange");
        definitions.add(exchange);
        var main = QueueBuilder.durable(prefix + ".order.payment-succeeded").quorum()
                .withArgument("x-delivery-limit", -1).build();
        definitions.add(main);
        definitions.add(BindingBuilder.bind(main).to(exchange).with("payment.succeeded"));
        var dead = QueueBuilder.durable(prefix + ".order.payment-dead").quorum().build();
        definitions.add(dead);
        definitions.add(BindingBuilder.bind(dead).to(exchange).with("payment.dead"));
        String[] values = delays.split(",");
        if (values.length != 3) throw new IllegalArgumentException("MQ重试必须配置三档延迟");
        for (int i=0; i<values.length; i++) {
            int delay = Integer.parseInt(values[i].strip());
            if (delay < 1) throw new IllegalArgumentException("MQ重试延迟必须大于零");
            var retry = QueueBuilder.durable(prefix + ".order.payment-retry-" + (i+1)).quorum()
                    .ttl(delay).deadLetterExchange(exchange.getName()).deadLetterRoutingKey("payment.succeeded")
                    .withArgument("x-dead-letter-strategy", "at-least-once")
                    .withArgument("x-overflow", "reject-publish").build();
            definitions.add(retry);
            definitions.add(BindingBuilder.bind(retry).to(exchange).with("payment.retry." + (i+1)));
        }
        return new Declarables(definitions);
    }
}
