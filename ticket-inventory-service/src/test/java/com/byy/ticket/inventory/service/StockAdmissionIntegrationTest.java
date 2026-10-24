package com.byy.ticket.inventory.service;

import com.byy.ticket.inventory.admission.*;
import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import com.byy.ticket.inventory.service.impl.InventoryServiceImpl;
import com.byy.ticket.testing.MysqlFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 真实 Redis + MySQL，覆盖代次丢失、最后一张竞争、取消墓碑及两份账本恢复。 */
@EnabledIfSystemProperty(named="ticket.test.prehold",matches="true")
class StockAdmissionIntegrationTest {
    static MysqlFixture db;static AnnotationConfigApplicationContext context;static LettuceConnectionFactory connection;
    static StockAdmissionService admission;static InventoryService inventory;static JdbcTemplate sql;static StringRedisTemplate redis;
    static final String PREFIX="ticket:test:prehold:"+UUID.randomUUID()+":";
    @Configuration @EnableTransactionManagement
    @MapperScan({"com.byy.ticket.inventory.mapper","com.byy.ticket.inventory.admission"})
    @Import(InventoryServiceImpl.class)
    static class Config {
        @Bean DataSource dataSource(){return db.dataSource;}
        @Bean SqlSessionFactory factory()throws Exception{return db.factory();}
        @Bean PlatformTransactionManager transactions(){return new DataSourceTransactionManager(db.dataSource);}
        @Bean Clock clock(){return Clock.system(ZoneOffset.ofHours(8));}
        @Bean HotStockGate gate(){return new HotStockGate(mock(StringRedisTemplate.class),new SimpleMeterRegistry(),false,"test:",2,15000);}
        @Bean StockAdmissionService admissions(com.byy.ticket.inventory.mapper.TicketStockMapper stocks,AdmissionMapper mapper,PlatformTransactionManager manager,Clock clock){
            return new StockAdmissionService(stocks,mapper,new RedisStockLedger(redis,PREFIX),manager,clock,new SimpleMeterRegistry(),true);
        }
    }
    @BeforeAll static void start()throws Exception {
        db=new MysqlFixture();connection=new LettuceConnectionFactory("127.0.0.1",Integer.getInteger("ticket.test.redis.port",16379));connection.afterPropertiesSet();connection.start();
        redis=new StringRedisTemplate(connection);context=new AnnotationConfigApplicationContext(Config.class);
        admission=context.getBean(StockAdmissionService.class);inventory=context.getBean(InventoryService.class);sql=new JdbcTemplate(db.dataSource);
    }
    @AfterAll static void stop()throws Exception {
        if(redis!=null)for(long tier=101;tier<=110;tier++)redis.delete(List.of(PREFIX+"{"+tier+"}:meta",PREFIX+"{"+tier+"}:holds"));
        if(context!=null)context.close();if(connection!=null)connection.destroy();if(db!=null)db.close();
    }
    void stock(long tier,int count){sql.update("INSERT INTO t_ticket_stock(ticket_tier_id,session_id,total_quantity,available_quantity) VALUES(?,1,?,?)",tier,count,count);admission.rebuild(tier);}
    ReserveStockDTO request(long tier,String id){return new ReserveStockDTO(id,1L,tier,1,LocalDateTime.now(ZoneOffset.ofHours(8)).plusMinutes(10).truncatedTo(ChronoUnit.MILLIS));}
    int available(long tier){return Integer.parseInt((String)redis.opsForHash().get(PREFIX+"{"+tier+"}:meta","available"));}
    @Test void fiftyRequestsCompeteForTenTicketsAcrossTwoStages()throws Exception {
        stock(101,10);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(12);
        try{
            List<Future<Boolean>> futures=new ArrayList<>();
            for(int i=0;i<50;i++){var request=request(101,"burst_"+i);futures.add(pool.submit(()->{start.await();try{admission.hold(request);inventory.reserve(request);return true;}catch(com.byy.ticket.inventory.exception.InventoryConflictException rejected){return false;}}));}
            start.countDown();int accepted=0;for(var future:futures)if(future.get(30,TimeUnit.SECONDS))accepted++;
            assertEquals(10,accepted);assertEquals(10,sql.queryForObject("SELECT reserved_quantity FROM t_ticket_stock WHERE ticket_tier_id=101",Integer.class));
            admission.rebuild(101);assertEquals(0,available(101));
        }finally{pool.shutdownNow();}
    }
    @Test void duplicateAndLostResponseDoNotDeductTwice(){
        stock(102,3);var request=request(102,"duplicate");admission.hold(request);admission.hold(request);assertEquals(2,available(102));
        var first=inventory.reserve(request);assertEquals(first.reservationId(),inventory.reserve(request).reservationId());
        admission.rebuild(102);assertEquals(2,available(102));
        inventory.release(first.reservationId());inventory.release(first.reservationId());admission.rebuild(102);assertEquals(3,available(102));
    }
    @Test void cancelledBeforeReserveCannotBeReopened(){
        stock(103,1);var request=request(103,"cancel_first");admission.cancel(request);
        assertThrows(RuntimeException.class,()->admission.hold(request));assertThrows(RuntimeException.class,()->inventory.reserve(request));
        admission.rebuild(103);assertEquals(1,available(103));
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id=103",Integer.class));
    }
    @Test void redisLossRebuildSubtractsDurablePendingHolds(){
        stock(104,2);var request=request(104,"redis_loss");admission.hold(request);
        redis.delete(List.of(PREFIX+"{104}:meta",PREFIX+"{104}:holds"));admission.rebuild(104);assertEquals(1,available(104));
        inventory.reserve(request);admission.rebuild(104);assertEquals(1,available(104));
    }
    @Test void oldEpochCannotCreateIntentAfterRebuild(){
        stock(105,1);var old=request(105,"old_epoch");
        var ledger=new RedisStockLedger(redis,PREFIX);String first=ledger.hold(105,"orphan",1,"orphan");
        admission.rebuild(105);assertEquals(1,available(105));
        assertNotEquals(first.split("\\|")[0],ledger.hold(105,old.orderId(),1,RedisStockLedger.fingerprint(new StockAdmission(old.orderId(),105L,1L,1,old.expiresAt(),"HELD"))).split("\\|")[0]);
    }
    @Test void lostRedisAcknowledgementCannotOverAllocateDurableIntent(){
        stock(106,1);admission.hold(request(106,"already_held"));
        // 模拟副本切换遗失一次已确认 Redis 扣减，MySQL 准入总账仍应拒绝第二位用户。
        redis.opsForHash().put(PREFIX+"{106}:meta","available","1");
        assertThrows(RuntimeException.class,()->admission.hold(request(106,"new_after_failover")));
        assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_stock_admission WHERE ticket_tier_id=106 AND status='HELD'",Integer.class));
        assertEquals(0,available(106));
    }
    @Test void retryUsesFirstExpiryEvenWhenCallerRecomputesIt(){
        stock(107,1);var first=request(107,"retry_expiry");var saved=admission.hold(first);
        var later=new ReserveStockDTO(first.orderId(),1L,107L,1,first.expiresAt().plusMinutes(1));
        assertEquals(saved.expiresAt(),admission.hold(later).expiresAt());
        assertThrows(RuntimeException.class,()->inventory.reserve(later));
        assertEquals("RESERVED",inventory.reserve(first).status());
    }
}
