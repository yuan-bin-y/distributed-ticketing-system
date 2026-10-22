package com.byy.ticket.inventory.service;

import com.byy.ticket.testing.MysqlFixture;
import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import com.byy.ticket.inventory.service.impl.InventoryServiceImpl;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named="ticket.test.mysql",matches="true")
class InventoryTransactionIntegrationTest {
    static MysqlFixture db;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate sql;
    static InventoryService inventory;
    @Configuration @EnableTransactionManagement
    @MapperScan("com.byy.ticket.inventory.mapper") @Import(InventoryServiceImpl.class)
    static class Config {
        @Bean DataSource dataSource(){return db.dataSource;}
        @Bean SqlSessionFactory sqlSessionFactory() throws Exception{return db.factory();}
        @Bean PlatformTransactionManager transactions(){return new DataSourceTransactionManager(db.dataSource);}
        @Bean Clock clock(){return Clock.system(ZoneOffset.ofHours(8));}
        @Bean HotStockGate gate(){return new HotStockGate(mock(org.springframework.data.redis.core.StringRedisTemplate.class),new SimpleMeterRegistry(),false,"test:",2,15000);}
    }
    @BeforeAll static void start() throws Exception {
        db=new MysqlFixture();
        try {context=new AnnotationConfigApplicationContext(Config.class);sql=new JdbcTemplate(db.dataSource);inventory=context.getBean(InventoryService.class);}
        catch(Exception failure){db.close();throw failure;}
    }
    @AfterAll static void close() throws Exception {if(context!=null)context.close();if(db!=null)db.close();}
    ReserveStockDTO request(long tier,String order) {
        return new ReserveStockDTO(order,1L,tier,1,LocalDateTime.now(ZoneOffset.ofHours(8)).plusMinutes(10).truncatedTo(ChronoUnit.MILLIS));
    }
    void stock(long tier,int quantity) {sql.update("INSERT INTO t_ticket_stock(ticket_tier_id,session_id,total_quantity,available_quantity) VALUES(?,1,?,?)",tier,quantity,quantity);}
    @Test void onlyOneOfTwoBuyersGetsLastTicket() throws Exception {
        stock(1,1);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<2;i++) {var request=request(1,"last_"+i);attempts.add(pool.submit(()->{start.await();try{inventory.reserve(request);return true;}catch(com.byy.ticket.inventory.exception.InventoryConflictException soldOut){return false;}}));}
            start.countDown();int won=0;for(var attempt:attempts)if(attempt.get(15,TimeUnit.SECONDS))won++;
            assertEquals(1,won);assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id=1",Integer.class));
            assertEquals(0,sql.queryForObject("SELECT available_quantity FROM t_ticket_stock WHERE ticket_tier_id=1",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void committedButLostResponseReturnsOriginalReservation() {
        stock(2,1);var request=request(2,"lost_response");inventory.reserve(request);
        var recovered=inventory.reserve(request);
        assertEquals(recovered.reservationId(),inventory.reserve(request).reservationId());
        assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id=2",Integer.class));
        assertEquals(1,sql.queryForObject("SELECT reserved_quantity FROM t_ticket_stock WHERE ticket_tier_id=2",Integer.class));
    }
    @Test void stockWriteFailureRollsBackInsertedReservation() {
        stock(3,1);
        sql.execute("CREATE TRIGGER fail_stock BEFORE UPDATE ON t_ticket_stock FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected stock failure'");
        try {assertThrows(RuntimeException.class,()->inventory.reserve(request(3,"rollback")));}
        finally {sql.execute("DROP TRIGGER fail_stock");}
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id=3",Integer.class));
        assertEquals(1,sql.queryForObject("SELECT available_quantity FROM t_ticket_stock WHERE ticket_tier_id=3",Integer.class));
    }
    @Test void confirmAndReleaseHaveOneTerminalWinner() throws Exception {
        stock(4,1);String id=inventory.reserve(request(4,"terminal_race")).reservationId();
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var confirm=pool.submit(()->{start.await();try{inventory.confirm(id);return true;}catch(com.byy.ticket.inventory.exception.InventoryConflictException conflict){return false;}});
            var release=pool.submit(()->{start.await();try{inventory.release(id);return true;}catch(com.byy.ticket.inventory.exception.InventoryConflictException conflict){return false;}});
            start.countDown();assertNotEquals(confirm.get(15,TimeUnit.SECONDS),release.get(15,TimeUnit.SECONDS));
            assertEquals(0,sql.queryForObject("SELECT reserved_quantity FROM t_ticket_stock WHERE ticket_tier_id=4",Integer.class));
            assertEquals(1,sql.queryForObject("SELECT available_quantity+sold_quantity FROM t_ticket_stock WHERE ticket_tier_id=4",Integer.class));
        } finally {pool.shutdownNow();}
    }
}
