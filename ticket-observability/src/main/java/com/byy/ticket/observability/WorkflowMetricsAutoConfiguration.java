package com.byy.ticket.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import javax.sql.DataSource;
import java.util.List;

/** 定时采集持久化任务事实；抓取接口只读内存，避免每次Prometheus抓取都查询业务库。 */
@AutoConfiguration(afterName="org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@EnableScheduling
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name="ticket.workflow-metrics.enabled",havingValue="true")
public class WorkflowMetricsAutoConfiguration {
    @Bean WorkflowMetrics workflowMetrics(DataSource dataSource, MeterRegistry registry,
            @Value("${spring.application.name}") String service) {
        return new WorkflowMetrics(dataSource,registry,service);
    }
    public static class WorkflowMetrics {
        private final DataSource dataSource;
        private final MeterRegistry registry;
        private final List<Sample> samples;
        private volatile double healthy=0, sampledAt=0;
        WorkflowMetrics(DataSource dataSource,MeterRegistry registry,String service) {
            this.dataSource=dataSource; this.registry=registry;
            samples=switch(service) {
                case "ticket-order-service" -> List.of(
                    new Sample("order_recovery","t_order","next_attempt_at", "status IN ('STOCK_PENDING','CLOSING','PAYMENT_CONFIRMING','REVERSAL_PENDING','PAID') AND next_attempt_at<=CURRENT_TIMESTAMP(3)"),
                    new Sample("order_review","t_order","updated_at","status='REVIEW_REQUIRED'"));
                case "ticket-payment-service" -> List.of(
                    new Sample("payment_outbox","t_outbox_event","created_at","status IN ('PENDING','SENDING','REVIEW_REQUIRED')"));
                case "ticket-event-service" -> List.of(
                    new Sample("event_preparation","t_ticket_tier","preparation_next_at","preparation_status='PENDING' AND preparation_next_at<=CURRENT_TIMESTAMP(3)"),
                    new Sample("event_review","t_ticket_tier","preparation_next_at","preparation_status='REVIEW_REQUIRED'"));
                default -> List.of();
            };
            for(Sample sample:samples) {
                Gauge.builder("ticket.workflow.pending",sample,s->s.pending).tag("workflow",sample.name).register(registry);
                Gauge.builder("ticket.workflow.oldest.seconds",sample,s->s.oldest).tag("workflow",sample.name).register(registry);
            }
            Gauge.builder("ticket.workflow.collector.healthy",this,m->m.healthy).register(registry);
            Gauge.builder("ticket.workflow.collector.sampled.timestamp.seconds",this,m->m.sampledAt).register(registry);
        }
        /** SQL只使用固定的代码内表名和条件，不接收外部输入；失败保留上次样本并标记失效。 */
        @Scheduled(fixedDelayString="${ticket.workflow-metrics.interval-ms:10000}")
        public void sample() {
            try(var connection=dataSource.getConnection()) {
                for(Sample sample:samples) {
                    try(var statement=connection.createStatement()) {
                        statement.setQueryTimeout(2);
                        try(var rows=statement.executeQuery("SELECT COUNT(*),COALESCE(GREATEST(0,TIMESTAMPDIFF(SECOND,MIN("+sample.time+"),CURRENT_TIMESTAMP(3))),0) FROM "+sample.table+" WHERE "+sample.where)) {
                            rows.next(); sample.pending=rows.getLong(1); sample.oldest=rows.getLong(2);
                        }
                    }
                }
                sampledAt=System.currentTimeMillis()/1000.0; healthy=1;
            } catch(Exception failure) {
                healthy=0; registry.counter("ticket.workflow.collector.errors").increment();
            }
        }
        private static class Sample {
            final String name,table,time,where;
            volatile double pending=Double.NaN,oldest=Double.NaN;
            Sample(String name,String table,String time,String where) {this.name=name;this.table=table;this.time=time;this.where=where;}
        }
    }
}
