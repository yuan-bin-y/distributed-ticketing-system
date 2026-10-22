package com.byy.ticket.event.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 校验声明的集群容量计划；实例信号量仍只负责本进程内的并发控制。 */
@Component
public class EventDatabaseBudget {
    private final long plannedLoads,plannedConnections;
    public EventDatabaseBudget(MeterRegistry metrics,
            @Value("${ticket.event-cache.database-concurrency:16}") int concurrency,
            @Value("${ticket.event-cache.planned-instances:1}") int instances,
            @Value("${ticket.event-cache.cluster-load-budget:16}") int budget,
            @Value("${spring.datasource.hikari.maximum-pool-size:20}") int pool,
            @Value("${ticket.event-cache.reserved-connections:4}") int reserve) {
        if(concurrency<1 || instances<1 || budget<1 || pool<1 || reserve<0
                || (long)concurrency+reserve>pool || (long)instances*concurrency>budget)
            throw new IllegalArgumentException("Event容量计划无效：回源并发加预留不能超过连接池，实例数乘回源并发不能超过集群预算");
        plannedLoads=(long)instances*concurrency;plannedConnections=(long)instances*pool;
        Gauge.builder("ticket.event.database.planned.loads",this,b->b.plannedLoads).register(metrics);
        Gauge.builder("ticket.event.database.planned.connections",this,b->b.plannedConnections).register(metrics);
    }
}
