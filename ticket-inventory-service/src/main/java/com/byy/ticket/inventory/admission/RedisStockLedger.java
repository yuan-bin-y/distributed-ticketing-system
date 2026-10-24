package com.byy.ticket.inventory.admission;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Lua 原子检查、扣量和记录请求。键使用同一 hash tag，兼容 Redis Cluster 槽限制。 */
@Component
public class RedisStockLedger {
    private static final DefaultRedisScript<String> HOLD = new DefaultRedisScript<>("""
        local e=redis.call('HGET',KEYS[1],'epoch')
        if not e then return 'NOT_READY' end
        local old=redis.call('HGET',KEYS[2],ARGV[1])
        if old then return old end
        local available=tonumber(redis.call('HGET',KEYS[1],'available'))
        if available<tonumber(ARGV[2]) then return 'SOLD_OUT' end
        redis.call('HINCRBY',KEYS[1],'available',-tonumber(ARGV[2]))
        redis.call('HSET',KEYS[2],ARGV[1],e..'|'..ARGV[3])
        return e..'|'..ARGV[3]
        """,String.class);
    private static final DefaultRedisScript<Long> RESET = new DefaultRedisScript<>("""
        redis.call('DEL',KEYS[2])
        redis.call('HSET',KEYS[1],'epoch',ARGV[1],'available',ARGV[2])
        for i=3,#ARGV,2 do redis.call('HSET',KEYS[2],ARGV[i],ARGV[1]..'|'..ARGV[i+1]) end
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final String prefix;
    public RedisStockLedger(StringRedisTemplate redis,@Value("${ticket.stock-admission.redis-prefix:ticket:stock-admission:}") String prefix) {
        this.redis=redis;this.prefix=prefix;
    }
    /** 返回代次与参数摘要；重复编号不会再次扣量。 */
    public String hold(long tier,String id,int quantity,String fingerprint) {
        return redis.execute(HOLD,keys(tier),id,String.valueOf(quantity),fingerprint);
    }
    /** 只能在库存行锁保护下，以数据库快照和待落实预留构建新的代次。 */
    public void reset(long tier,String epoch,int available,List<StockAdmission> pending) {
        List<String> args=new ArrayList<>(List.of(epoch,String.valueOf(available)));
        for(var item:pending){args.add(item.orderNo());args.add(fingerprint(item));}
        redis.execute(RESET,keys(tier),args.toArray());
    }
    public static String fingerprint(StockAdmission item) {
        return item.sessionId()+":"+item.ticketTierId()+":"+item.quantity();
    }
    private List<String> keys(long tier){String key=prefix+"{"+tier+"}";return List.of(key+":meta",key+":holds");}
}
