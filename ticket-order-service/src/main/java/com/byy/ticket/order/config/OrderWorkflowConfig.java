package com.byy.ticket.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用订单恢复任务和配置绑定；调度开关由 OrderWorkflowProperties 控制。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OrderWorkflowProperties.class)
public class OrderWorkflowConfig {
    /** 预留后可能立即释放，租约需覆盖两次 HTTP 调用及数据库处理余量。 */
    public OrderWorkflowConfig(OrderWorkflowProperties workflow, InventoryClientProperties inventory) {
        java.time.Duration minimum = inventory.connectTimeout().plus(inventory.readTimeout())
                .multipliedBy(2).plusSeconds(5);
        if (workflow.leaseDuration().compareTo(minimum) < 0) {
            throw new IllegalArgumentException("订单任务租约必须覆盖两次库存调用超时并留出 5 秒余量");
        }
    }
}
