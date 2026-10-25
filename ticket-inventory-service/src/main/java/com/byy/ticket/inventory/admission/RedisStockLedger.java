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
        local selected=cjson.decode(ARGV[4])
        local mode=redis.call('HGET',KEYS[1],'seatMode') or '0'
        if mode=='1' and #selected==0 then return 'SEAT_REQUIRED' end
        if mode=='0' and #selected>0 then return 'NO_SEAT_MODE' end
        for _,seat in ipairs(selected) do if redis.call('HGET',KEYS[3],seat)~='AVAILABLE' then return 'SEAT_TAKEN' end end
        local available=tonumber(redis.call('HGET',KEYS[1],'available'))
        if available<tonumber(ARGV[2]) then return 'SOLD_OUT' end
        redis.call('HINCRBY',KEYS[1],'available',-tonumber(ARGV[2]))
        redis.call('HSET',KEYS[2],ARGV[1],e..'|'..ARGV[3])
        for _,seat in ipairs(selected) do redis.call('HSET',KEYS[3],seat,ARGV[1]) end
        return e..'|'..ARGV[3]
        """,String.class);
    private static final DefaultRedisScript<Long> RESET = new DefaultRedisScript<>("""
        local value=cjson.decode(ARGV[1])
        redis.call('DEL',KEYS[2],KEYS[3])
        redis.call('HMSET',KEYS[1],'epoch',value.epoch,'available',value.available,'seatMode',value.seatMode)
        for _,item in ipairs(value.pending) do redis.call('HSET',KEYS[2],item.id,value.epoch..'|'..item.fingerprint) end
        for _,seat in ipairs(value.seats) do redis.call('HSET',KEYS[3],seat,'AVAILABLE') end
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final String prefix;
    public RedisStockLedger(StringRedisTemplate redis,@Value("${ticket.stock-admission.redis-prefix:ticket:stock-admission:}") String prefix) {
        this.redis=redis;this.prefix=prefix;
    }
    /** 返回代次与参数摘要；重复编号不会再次扣量。 */
    public String hold(long tier,String id,int quantity,String fingerprint) {
        return hold(tier,id,quantity,fingerprint,List.of());
    }
    public String hold(long tier,String id,int quantity,String fingerprint,List<String> seats) {
        return redis.execute(HOLD,keys(tier),id,String.valueOf(quantity),fingerprint,new tools.jackson.databind.ObjectMapper().writeValueAsString(seats));
    }
    /** 只能在库存行锁保护下，以数据库快照和待落实预留构建新的代次。 */
    public void reset(long tier,String epoch,int available,List<StockAdmission> pending) {
        reset(tier,epoch,available,pending,false,List.of());
    }
    public void reset(long tier,String epoch,int available,List<StockAdmission> pending,boolean seatMode,List<String> seats){
        var records=pending.stream().map(item->Map.of("id",item.orderNo(),"fingerprint",fingerprint(item))).toList();
        var command=Map.of("epoch",epoch,"available",available,"pending",records,"seatMode",seatMode?1:0,"seats",seats);
        redis.execute(RESET,keys(tier),new tools.jackson.databind.ObjectMapper().writeValueAsString(command));
    }
    public static String fingerprint(StockAdmission item) {
        return item.sessionId()+":"+item.ticketTierId()+":"+item.quantity()+":"+item.seatFingerprint();
    }
    private List<String> keys(long tier){String key=prefix+"{"+tier+"}";return List.of(key+":meta",key+":holds",key+":seats");}
}
