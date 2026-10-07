package com.byy.ticket.event.cache;
import com.byy.ticket.common.exception.ResourceNotFoundException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.*;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** 缓存VO：跨实例重建、空结果、随机TTL、提交后版本切换及受控回源。 */
@Component
public class EventQueryCache {
    private static final DefaultRedisScript<Long> SAVE=new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1])==ARGV[1] then redis.call('psetex',KEYS[2],ARGV[3],ARGV[2]); "
        +"redis.call('del',KEYS[1]); return 1 end; return 0",Long.class);
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) end; return 0",Long.class);
    private final StringRedisTemplate redis; private final ObjectMapper json;
    private final boolean enabled; private final String prefix;
    private final EventCacheTtl ttl; private final long waitMs,leaseMs; private final Semaphore database;
    public final LongAdder hits=new LongAdder(),loads=new LongAdder(),waits=new LongAdder(),
        failures=new LongAdder(),busy=new LongAdder();
    public EventQueryCache(StringRedisTemplate redis,ObjectMapper json,
        @Value("${ticket.event-cache.enabled:true}") boolean enabled,
        @Value("${ticket.event-cache.prefix:ticket:{event-cache}:}") String prefix,
        EventCacheTtl ttl,
        @Value("${ticket.event-cache.wait-ms:1500}") long waitMs,
        @Value("${ticket.event-cache.lease-ms:8000}") long leaseMs,
        @Value("${ticket.event-cache.database-concurrency:16}") int concurrency) {
        if(waitMs<1||leaseMs<100||concurrency<1)throw new IllegalArgumentException("缓存配置不合法");
        this.redis=redis;this.json=json;this.enabled=enabled;this.prefix=prefix;
        this.ttl=ttl;this.waitMs=waitMs;this.leaseMs=leaseMs;database=new Semaphore(concurrency);
    }
    /** 缓存开关关闭、深页查询或Redis故障时仍执行数据库并发保护。 */
    public <T> T uncached(Supplier<T> loader){return load(loader);}
    /** 先读缓存，再竞争重建权限；等待者读取重建结果，超时则拒绝过量回源。 */
    public <T> T get(String name,JavaType type,Supplier<T> loader) {
        if(!enabled)return load(loader);
        String key;
        try {
            String epoch=redis.opsForValue().get(prefix+"epoch");
            if(epoch==null){
                redis.opsForValue().setIfAbsent(prefix+"epoch",UUID.randomUUID().toString());
                epoch=redis.opsForValue().get(prefix+"epoch");
                if(epoch==null){failures.increment();return load(loader);}
            }
            key=prefix+epoch+":"+name;
        }catch(DataAccessException unavailable){failures.increment();return load(loader);}
        String lock=key+":lock",token=UUID.randomUUID().toString();
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(waitMs);
        while(true) {
            boolean acquired;
            try {
                T cached=read(key,type);
                if(cached!=null){hits.increment();return cached;}
                acquired=Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock,token,Duration.ofMillis(leaseMs)));
            }catch(DataAccessException unavailable){failures.increment();return load(loader);}
            if(acquired) {
                try {
                    T cached;
                    try{cached=read(key,type);}
                    catch(DataAccessException unavailable){failures.increment();return load(loader);}
                    if(cached!=null){hits.increment();return cached;}
                    T value;
                    try{value=load(loader);}
                    catch(ResourceNotFoundException missing){save(lock,key,token,"null",5000);throw missing;}
                    // 每次写入读取同一个TTL快照；已有缓存和固定空结果TTL不变。
                    long ttlMs=ttl.millis();
                    save(lock,key,token,json.writeValueAsString(value),
                         ttlMs+ThreadLocalRandom.current().nextLong(Math.max(1,ttlMs/5)));
                    return value;
                }finally{
                    try{redis.execute(RELEASE,List.of(lock),token);}
                    catch(DataAccessException unavailable){failures.increment();}
                }
            }
            waits.increment();
            if(System.nanoTime()>=deadline){busy.increment();throw new CacheBusyException();}
            try{Thread.sleep(15+ThreadLocalRandom.current().nextInt(15));}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CacheBusyException();}
        }
    }
    /** 把VO载荷反序列化；空结果返回404，损坏载荷按值比较后删除。 */
    private <T> T read(String key,JavaType type) {
        String payload=redis.opsForValue().get(key);
        if(payload==null)return null;
        if("null".equals(payload))throw new ResourceNotFoundException("活动不存在或未发布");
        try{return json.readValue(payload,type);}
        catch(tools.jackson.core.JacksonException invalid){
            failures.increment();
            // 仅删除仍为该坏载荷的key，避免误删另一个请求写入的新缓存。
            redis.execute(RELEASE,List.of(key),payload);return null;
        }
    }
    /** 原子核对token再写缓存和释放锁，租约过期的旧持有者不能覆盖新缓存。 */
    private void save(String lock,String key,String token,String payload,long ttl) {
        try{redis.execute(SAVE,List.of(lock,key),token,payload,Long.toString(ttl));}
        catch(DataAccessException unavailable){failures.increment();}
    }
    /** 限制本实例同时进行的展示查询，并保证异常情况下也归还额度。 */
    private <T> T load(Supplier<T> loader) {
        boolean acquired=false;
        try{
            acquired=database.tryAcquire(100,TimeUnit.MILLISECONDS);
            if(!acquired){busy.increment();throw new CacheBusyException();}
            loads.increment();return loader.get();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CacheBusyException();}
        finally{if(acquired)database.release();}
    }
    /** 提交后切换全局展示版本；旧查询写入的旧版本key只等待TTL回收。 */
    public void invalidateAfterCommit() {
        if(!enabled)return;
        Runnable invalidate=()->{
            try{redis.opsForValue().set(prefix+"epoch",UUID.randomUUID().toString());}
            catch(DataAccessException unavailable){
                failures.increment();
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("活动缓存失效通知失败，缓存按TTL更新");
            }
        };
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){invalidate.run();}
            });
        else invalidate.run();
    }
}
