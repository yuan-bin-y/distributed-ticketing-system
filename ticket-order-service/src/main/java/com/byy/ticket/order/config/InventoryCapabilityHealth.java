package com.byy.ticket.order.config;

import com.byy.ticket.order.client.StockAdmissionClient;
import org.springframework.boot.health.contributor.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 管理探针只读取最近状态；网络检查在独立调度器执行，启动期间保持未就绪。 */
@Component("inventoryCapability")
public class InventoryCapabilityHealth implements HealthIndicator {
    private final StockAdmissionClient client;
    private volatile Health state = Health.outOfService().withDetail("reason", "等待库存协议检查").build();

    public InventoryCapabilityHealth(StockAdmissionClient client) { this.client = client; }

    @Scheduled(fixedDelayString = "${ticket.inventory-capability.interval-ms:3000}", scheduler = "inventoryCapabilityScheduler")
    public void check() {
        try { client.requireCompatible(); state = Health.up().build(); }
        catch (RuntimeException failure) { state = Health.down().withDetail("reason", failure.getMessage()).build(); }
    }

    @Override public Health health() { return state; }
}
