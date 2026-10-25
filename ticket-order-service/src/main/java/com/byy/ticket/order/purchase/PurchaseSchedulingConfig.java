package com.byy.ticket.order.purchase;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Broker故障等待不能占用订单履约调度线程；三个用途使用独立且有界的调度池。 */
@Configuration
public class PurchaseSchedulingConfig {
    @Bean("purchaseRelayScheduler")
    public ThreadPoolTaskScheduler relay() {
        return scheduler("purchase-relay-", 1);
    }

    @Bean("purchaseRecoveryScheduler")
    public ThreadPoolTaskScheduler recovery() {
        return scheduler("purchase-recovery-", 1);
    }

    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler business() {
        return scheduler("order-business-", 2);
    }

    @Bean("orderReviewScheduler")
    public ThreadPoolTaskScheduler review() {
        return scheduler("order-review-", 1);
    }

    private ThreadPoolTaskScheduler scheduler(String name, int size) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(size);
        scheduler.setThreadNamePrefix(name);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
