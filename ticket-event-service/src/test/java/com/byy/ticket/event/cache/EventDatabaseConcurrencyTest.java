package com.byy.ticket.event.cache;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EventDatabaseConcurrencyTest {
    @Test void twoInstancesSharePlannedBudgetAndReturnPermitsAfterFailure() throws Exception {
        var left=new EventQueryCache(mock(org.springframework.data.redis.core.StringRedisTemplate.class),new ObjectMapper(),false,"test:",new EventCacheTtl(1000),1000,8000,2);
        var right=new EventQueryCache(mock(org.springframework.data.redis.core.StringRedisTemplate.class),new ObjectMapper(),false,"test:",new EventCacheTtl(1000),1000,8000,2);
        var pool=Executors.newFixedThreadPool(12);var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
        var active=new AtomicInteger();var maximum=new AtomicInteger();List<Future<?>> tasks=new ArrayList<>();
        try {
            for(int i=0;i<4;i++) {var cache=i%2==0?left:right;tasks.add(pool.submit(()->cache.uncached(()->{
                int current=active.incrementAndGet();maximum.accumulateAndGet(current,Math::max);entered.countDown();
                try {release.await(5,TimeUnit.SECONDS);return 1;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}finally{active.decrementAndGet();}
            })));}
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(CacheBusyException.class,()->left.uncached(()->99));
            assertThrows(CacheBusyException.class,()->right.uncached(()->99));
            release.countDown();for(var task:tasks)task.get(5,TimeUnit.SECONDS);
            assertEquals(4,maximum.get());
            assertThrows(IllegalStateException.class,()->left.uncached(()->{throw new IllegalStateException("query failure");}));
            assertEquals(1,left.uncached(()->1));assertEquals(1,right.uncached(()->1));
        }finally{release.countDown();pool.shutdownNow();}
    }
}
