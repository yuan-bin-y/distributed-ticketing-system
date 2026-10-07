package com.byy.ticket.inventory.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.TransientDataAccessException;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式开启后使用真实Redis，验证两个独立准入器共享额度及租约回收。 */
@EnabledIfSystemProperty(named="ticket.test.redis", matches="true")
class HotStockGateIntegrationTest {
    @Test void crossInstanceAdmissionAndLeaseRecovery() throws Exception {
        var connection=new LettuceConnectionFactory("127.0.0.1",Integer.getInteger("ticket.test.redis.port",16379));
        connection.afterPropertiesSet(); connection.start();
        var redis=new StringRedisTemplate(connection);
        String prefix="ticket:test:hot:"+UUID.randomUUID()+":";
        var registry=new SimpleMeterRegistry();
        var left=new HotStockGate(redis,registry,true,prefix,2,15000);
        var right=new HotStockGate(redis,registry,true,prefix,2,15000);
        var workers=Executors.newFixedThreadPool(16);
        List<HotStockGate.Permit> held=new CopyOnWriteArrayList<>();
        try {
            var start=new CountDownLatch(1);
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<48;i++) {
                HotStockGate gate=i%2==0?left:right;
                attempts.add(workers.submit(()->{
                    start.await();
                    try { held.add(gate.acquire(1L)); return true; }
                    catch(TransientDataAccessException busy) {return false;}
                }));
            }
            start.countDown(); int accepted=0;
            for(var attempt:attempts) if(attempt.get(10,TimeUnit.SECONDS)) accepted++;
            assertEquals(2,accepted);
            assertEquals(46,registry.counter("ticket.inventory.admission","outcome","rejected").count());
            try(var independent=right.acquire(2L)) {assertNotNull(independent);}
            for(var permit:held) permit.close(); held.clear();
            try(var restored=right.acquire(1L)) {assertNotNull(restored);}

            // 模拟旧进程没有释放；租约后新令牌获得资格，旧释放不能删掉新令牌。
            var shortLease=new HotStockGate(redis,registry,true,prefix,1,1000);
            var old=shortLease.acquire(3L);
            Thread.sleep(1150);
            try(var fresh=shortLease.acquire(3L)) {
                old.close();
                assertEquals(1L,redis.opsForZSet().zCard(prefix+"{3}"));
            }
            assertEquals(0L,redis.opsForZSet().zCard(prefix+"{3}"));
        } finally {
            for(var permit:held) permit.close();
            workers.shutdownNow(); redis.delete(List.of(prefix+"{1}",prefix+"{2}",prefix+"{3}"));
            connection.destroy(); registry.close();
        }
    }
    @Test void redisFailureRejectsWithoutInventingStockResult() {
        var redis=new StringRedisTemplate() {
            @Override public <T> T execute(org.springframework.data.redis.core.script.RedisScript<T> script,
                    List<String> keys,Object... args) {
                throw new org.springframework.data.redis.RedisConnectionFailureException("simulated Redis outage");
            }
        };
        var registry=new SimpleMeterRegistry();
        try {
            var gate=new HotStockGate(redis,registry,true,"test:",2,15000);
            assertThrows(TransientDataAccessException.class,()->gate.acquire(1L));
            assertEquals(1,registry.counter("ticket.inventory.admission","outcome","redis_error").count());
            var disabled=new HotStockGate(redis,registry,false,"test:",2,15000);
            try(var permit=disabled.acquire(1L)) {assertNotNull(permit);}
        } finally {registry.close();}
    }
}
