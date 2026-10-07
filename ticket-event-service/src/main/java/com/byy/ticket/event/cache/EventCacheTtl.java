package com.byy.ticket.event.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

/** 只动态更新缓存基础TTL，不重建缓存对象、信号量或正在使用的锁。 */
@Component
public class EventCacheTtl {
    public static final String KEY = "ticket.event-cache.ttl-ms";
    private final Environment environment;
    private volatile long millis;

    /** 启动时校验有效配置，避免带着非法TTL开始服务。 */
    @Autowired
    public EventCacheTtl(Environment environment) {
        this.environment = environment;
        this.millis = parse(environment.getProperty(KEY, "60000"));
    }

    /** 隔离验证使用固定TTL，不连接配置中心。 */
    public EventCacheTtl(long millis) {
        this.environment = null;
        this.millis = parse(Long.toString(millis));
    }

    /** 缓存写入前读取当前快照，volatile保证其他请求线程能看见更新。 */
    public long millis() { return millis; }

    /** Nacos更新Spring环境后触发；坏配置不覆盖上一次有效值。 */
    @EventListener
    public void refresh(EnvironmentChangeEvent event) {
        if (environment == null || !event.getKeys().contains(KEY)) { return; }
        try {
            long updated = parse(environment.getProperty(KEY, "60000"));
            long previous = millis;
            millis = updated;
            if (previous != updated) {
                LoggerFactory.getLogger(getClass()).info("活动缓存基础TTL更新：{}ms → {}ms", previous, updated);
            }
        } catch (IllegalArgumentException invalid) {
            LoggerFactory.getLogger(getClass()).warn("活动缓存TTL配置无效，保留{}ms；允许范围100至86400000ms", millis);
        }
    }

    /** 限制为100毫秒至24小时，拒绝非整数、负数和过大值。 */
    private static long parse(String value) {
        long parsed = Long.parseLong(value.trim());
        if (parsed < 100 || parsed > 86_400_000) {
            throw new IllegalArgumentException("缓存TTL必须在100至86400000ms之间");
        }
        return parsed;
    }
}
