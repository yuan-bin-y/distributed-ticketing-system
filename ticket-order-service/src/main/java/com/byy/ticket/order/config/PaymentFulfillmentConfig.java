package com.byy.ticket.order.config;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

@Configuration
public class PaymentFulfillmentConfig {
    @Bean("paymentFulfillmentExecutor")
    ThreadPoolTaskExecutor executor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4); executor.setMaxPoolSize(4); executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("payment-fulfill-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(15);
        return executor;
    }
}
