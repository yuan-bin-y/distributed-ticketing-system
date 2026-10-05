package com.byy.ticket.order.config;
import javax.sql.DataSource;
import com.byy.ticket.common.trace.PerformanceSpan;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;
/** 性能诊断时只读采样连接池，每250ms一行；默认不创建此组件。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="ticket.perf.enabled",havingValue="true")
public class PerformancePoolSampler {
    private final DataSource source;
        /** 只在诊断开关开启、尚无其他指标插件时注册，不替换已有监控。 */
    public PerformancePoolSampler(DataSource source)throws java.sql.SQLException{
        this.source=source;
        var hikari=source.unwrap(HikariDataSource.class);
        if(hikari.getMetricsTrackerFactory()==null&&hikari.getMetricRegistry()==null){
            hikari.setMetricsTrackerFactory((name,stats)->new IMetricsTracker(){
                @Override public void recordConnectionAcquiredNanos(long nanos){PerformanceSpan.record("pool.acquire",nanos);}
                @Override public void recordConnectionUsageMillis(long millis){PerformanceSpan.record("pool.hold",millis*1_000_000);}
            });
        }
    }
    @Scheduled(fixedDelay=250)
    public void sample(){
        try{
            var pool=source.unwrap(HikariDataSource.class).getHikariPoolMXBean();
            if(pool!=null)LoggerFactory.getLogger("ticket.performance").info("POOL active={} idle={} pending={} total={}",pool.getActiveConnections(),pool.getIdleConnections(),pool.getThreadsAwaitingConnection(),pool.getTotalConnections());
        }catch(java.sql.SQLException error){LoggerFactory.getLogger("ticket.performance").warn("POOL unavailable type={}",error.getClass().getSimpleName());}
    }
}