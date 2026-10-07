package com.byy.ticket.inventory.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.dao.TransientDataAccessResourceException;
import java.util.List;
import java.util.UUID;

/** 跨实例票档准入：在开启数据库事务之前削减热点行竞争，不把 Redis 当库存账本。 */
@Component
public class HotStockGate {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
        if redis.replicate_commands then redis.replicate_commands() end
        local tm=redis.call('TIME'); local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
        redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
        if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[2]) then return 0 end
        redis.call('ZADD',KEYS[1],now+tonumber(ARGV[3]),ARGV[1])
        redis.call('PEXPIRE',KEYS[1],tonumber(ARGV[3])*2)
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "return redis.call('ZREM',KEYS[1],ARGV[1])", Long.class);
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final String prefix;
    private final int permits;
    private final long leaseMs;

    public HotStockGate(StringRedisTemplate redis, MeterRegistry metrics,
            @Value("${ticket.hot-stock.enabled:false}") boolean enabled,
            @Value("${ticket.hot-stock.prefix:ticket:hot-stock:}") String prefix,
            @Value("${ticket.hot-stock.permits:2}") int permits,
            @Value("${ticket.hot-stock.lease-ms:15000}") long leaseMs) {
        if (permits<1 || leaseMs<1000 || prefix.isBlank()) throw new IllegalArgumentException("热点准入配置无效");
        this.redis=redis; this.metrics=metrics; this.enabled=enabled;
        this.prefix=prefix; this.permits=permits; this.leaseMs=leaseMs;
    }

    /** 同一票档共享有限额度；不等待、不持有数据库连接。Redis异常时拒绝，交给订单原有补偿。 */
    public Permit acquire(Long tierId) {
        if (!enabled) return new Permit(null,null);
        String key=prefix+"{"+tierId+"}", token=UUID.randomUUID().toString();
        Long result;
        try { result=redis.execute(ACQUIRE,List.of(key),token,Integer.toString(permits),Long.toString(leaseMs)); }
        catch (RuntimeException failure) {
            count("redis_error");
            throw new TransientDataAccessResourceException("库存准入暂时不可用，请按原订单重试",failure);
        }
        if (!Long.valueOf(1).equals(result)) {
            count("rejected");
            throw new com.byy.ticket.inventory.exception.HotStockBusyException();
        }
        count("accepted"); return new Permit(key,token);
    }
    private void count(String outcome) { metrics.counter("ticket.inventory.admission","outcome",outcome).increment(); }

    /** 只释放自己的随机令牌；崩溃后的额度由租约回收。释放失败不覆盖已经提交的业务结果。 */
    public final class Permit implements AutoCloseable {
        private final String key,token;
        private Permit(String key,String token) {this.key=key;this.token=token;}
        @Override public void close() {
            if(key==null)return;
            try {redis.execute(RELEASE,List.of(key),token);}
            catch(RuntimeException failure) {count("release_error");}
        }
    }
}
