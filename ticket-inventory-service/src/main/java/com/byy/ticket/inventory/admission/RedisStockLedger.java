package com.byy.ticket.inventory.admission;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
        if redis.call('GET',KEYS[4])~=ARGV[1] or redis.call('EXISTS',KEYS[5])==0 or redis.call('EXISTS',KEYS[6])==0 then return 0 end
        redis.call('RENAME',KEYS[5],KEYS[2]); redis.call('PERSIST',KEYS[2])
        redis.call('RENAME',KEYS[6],KEYS[3]); redis.call('PERSIST',KEYS[3])
        redis.call('HDEL',KEYS[2],'@building'); redis.call('HDEL',KEYS[3],'@building')
        redis.call('HMSET',KEYS[1],'epoch',ARGV[1],'available',ARGV[2],'seatMode',ARGV[3])
        redis.call('DEL',KEYS[4])
        return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    private final String prefix;
    private final tools.jackson.databind.ObjectMapper json;
    public RedisStockLedger(StringRedisTemplate redis,String prefix) {
        this(redis,prefix,new tools.jackson.databind.ObjectMapper());
    }
    @Autowired
    public RedisStockLedger(StringRedisTemplate redis,@Value("${ticket.stock-admission.redis-prefix:ticket:stock-admission:}") String prefix,
                            tools.jackson.databind.ObjectMapper json) {
        this.redis=redis;this.prefix=prefix;this.json=json;
    }
    /** 返回代次与参数摘要；重复编号不会再次扣量。 */
    public String hold(long tier,String id,int quantity,String fingerprint) {
        return hold(tier,id,quantity,fingerprint,List.of());
    }
    public String hold(long tier,String id,int quantity,String fingerprint,List<String> seats) {
        return redis.execute(HOLD,keys(tier),id,String.valueOf(quantity),fingerprint,json.writeValueAsString(seats));
    }
    /** 只能在库存行锁保护下，以数据库快照和待落实预留构建新的代次。 */
    public void reset(long tier,String epoch,int available,List<StockAdmission> pending) {
        reset(tier,epoch,available,pending,false,List.of());
    }
    public void reset(long tier,String epoch,int available,List<StockAdmission> pending,boolean seatMode,List<String> seats){
        var live=keys(tier);String stem=prefix+"{"+tier+"}:build:"+epoch;
        var staging=List.of(stem+":proof",stem+":holds",stem+":seats");
        // 证明键先到期，避免构建期间暂停过久后切换到不完整的临时哈希。
        redis.opsForValue().set(staging.get(0),epoch,java.time.Duration.ofMinutes(2));
        try {
            for(String key:staging.subList(1,3)){
                redis.opsForHash().put(key,"@building","1");redis.expire(key,java.time.Duration.ofMinutes(2));
            }
            for(int offset=0;offset<pending.size();offset+=256){
                Map<String,String> batch=new HashMap<>();
                for(var item:pending.subList(offset,Math.min(offset+256,pending.size())))batch.put(item.orderNo(),epoch+"|"+fingerprint(item));
                redis.opsForHash().putAll(staging.get(1),batch);
            }
            for(int offset=0;offset<seats.size();offset+=256){
                Map<String,String> batch=new HashMap<>();
                for(String seat:seats.subList(offset,Math.min(offset+256,seats.size())))batch.put(seat,"AVAILABLE");
                redis.opsForHash().putAll(staging.get(2),batch);
            }
            var all=new ArrayList<>(live);all.addAll(staging);
            if(!Long.valueOf(1).equals(redis.execute(RESET,all,epoch,String.valueOf(available),seatMode?"1":"0")))
                throw new IllegalStateException("Redis预扣构建已过期，请重新核对");
        } finally {redis.delete(staging);}
    }
    public static String fingerprint(StockAdmission item) {
        return item.sessionId()+":"+item.ticketTierId()+":"+item.quantity()+":"+item.seatFingerprint();
    }
    private List<String> keys(long tier){String key=prefix+"{"+tier+"}";return List.of(key+":meta",key+":holds",key+":seats");}
}
