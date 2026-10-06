package com.byy.ticket.event.cache;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.slf4j.LoggerFactory;

/** 压测开关默认关闭，只记录缓存计数；实际SQL次数由MySQL语句统计核对。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name="ticket.event-cache.metrics-enabled",havingValue="true")
public class EventQueryMetrics {
    private final EventQueryCache cache;
    public EventQueryMetrics(EventQueryCache cache){this.cache=cache;}
    @Scheduled(fixedDelay=500)
    public void snapshot(){
        LoggerFactory.getLogger(getClass()).info("EVENT_CACHE_METRICS hits={} loads={} waits={} redisFailures={} busy={}",
            cache.hits.sum(),cache.loads.sum(),cache.waits.sum(),cache.failures.sum(),cache.busy.sum());
    }
}
