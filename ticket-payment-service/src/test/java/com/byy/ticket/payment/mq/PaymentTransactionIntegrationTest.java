package com.byy.ticket.payment.mq;

import com.byy.ticket.testing.MysqlFixture;
import com.byy.ticket.payment.config.PaymentProperties;
import com.byy.ticket.payment.dto.payment.PaymentCreateDTO;
import com.byy.ticket.payment.service.*;
import com.byy.ticket.payment.service.impl.PaymentServiceImpl;
import com.byy.ticket.payment.mapper.OutboxEventMapper;
import com.byy.ticket.observability.TraceSupport;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.UUID;
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
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="ticket.test.mysql",matches="true")
class PaymentTransactionIntegrationTest {
    static MysqlFixture db;static AnnotationConfigApplicationContext context;static JdbcTemplate sql;
    @Configuration @EnableTransactionManagement @MapperScan("com.byy.ticket.payment.mapper")
    @Import({PaymentServiceImpl.class,PaymentOutboxService.class})
    static class Config {
        @Bean DataSource dataSource(){return db.dataSource;}
        @Bean SqlSessionFactory sqlSessionFactory() throws Exception{return db.factory();}
        @Bean PlatformTransactionManager transactions(){return new DataSourceTransactionManager(db.dataSource);}
        @Bean Clock clock(){return Clock.system(ZoneOffset.ofHours(8));}
        @Bean PaymentProperties properties(){return new PaymentProperties(true,false);}
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean TraceSupport traces(){var trace=mock(TraceSupport.class);when(trace.capture()).thenReturn(new TraceSupport.Snapshot(null,null));return trace;}
    }
    @BeforeAll static void start() throws Exception {db=new MysqlFixture();try{context=new AnnotationConfigApplicationContext(Config.class);sql=new JdbcTemplate(db.dataSource);}catch(Exception e){db.close();throw e;}}
    @AfterAll static void close() throws Exception {if(context!=null)context.close();if(db!=null)db.close();}
    String create() {
        return context.getBean(PaymentService.class).create(new PaymentCreateDTO(UUID.randomUUID().toString().replace("-",""),1L,new BigDecimal("199.00"),LocalDateTime.now(ZoneOffset.ofHours(8)).plusMinutes(10).truncatedTo(ChronoUnit.MILLIS))).paymentNo();
    }
    @Test void outboxInsertFailureRollsBackPaymentSuccess() {
        String payment=create();sql.execute("CREATE TRIGGER fail_outbox BEFORE INSERT ON t_outbox_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected outbox failure'");
        try {assertThrows(RuntimeException.class,()->context.getBean(PaymentService.class).simulateSuccess(1L,payment));}
        finally {sql.execute("DROP TRIGGER fail_outbox");}
        assertEquals("CREATED",sql.queryForObject("SELECT status FROM t_payment WHERE payment_no=?",String.class,payment));
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM t_outbox_event WHERE aggregate_id=?",Integer.class,payment));
    }
    @Test void brokerOutageKeepsCommittedPaymentAndOriginalOutboxUntilRecovery() throws Exception {
        String payment=create();context.getBean(PaymentService.class).simulateSuccess(1L,payment);
        var events=context.getBean(OutboxEventMapper.class);var original=events.selectPaymentSucceeded(payment);
        var publisher=mock(PaymentEventPublisher.class);doThrow(new java.net.SocketTimeoutException()).doNothing().when(publisher).publish(any());
        var job=new PaymentOutboxJob(events,publisher,new OutboxRetryPolicy(3,Duration.ofHours(1)),new OutboxMessageValidator(new ObjectMapper()),context.getBean(Clock.class));
        job.publishDue();var retry=events.selectPaymentSucceeded(payment);
        assertEquals("PENDING",retry.getStatus());assertEquals(original.getEventId(),retry.getEventId());
        assertEquals("SUCCESS",sql.queryForObject("SELECT status FROM t_payment WHERE payment_no=?",String.class,payment));
        sql.update("UPDATE t_outbox_event SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE aggregate_id=?",payment);
        job.publishDue();assertEquals("PUBLISHED",events.selectPaymentSucceeded(payment).getStatus());
    }
    @Test void expiredSenderCannotOverwriteNewLease() {
        String payment=create();context.getBean(PaymentService.class).simulateSuccess(1L,payment);
        var events=context.getBean(OutboxEventMapper.class);var event=events.selectPaymentSucceeded(payment);
        assertEquals(1,events.claim(event.getId(),"old"));
        sql.update("UPDATE t_outbox_event SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",event.getId());
        assertEquals(1,events.claim(event.getId(),"new"));
        assertEquals(0,events.published(event.getId(),"old"));assertEquals(0,events.review(event.getId(),"old","INVALID_PAYLOAD","test"));
        assertEquals(1,events.published(event.getId(),"new"));
    }
    @Test void reviewedRedriveKeepsIdentityAndCommitsAuditWithState() throws Exception {
        String payment=create();context.getBean(PaymentService.class).simulateSuccess(1L,payment);
        var events=context.getBean(OutboxEventMapper.class);var event=events.selectPaymentSucceeded(payment);
        assertEquals(1,events.claim(event.getId(),"operator-test"));
        assertEquals(1,events.review(event.getId(),"operator-test","ATTEMPT_BUDGET_EXHAUSTED","test outage"));
        var source=java.nio.file.Path.of("../deploy/mq/OutboxReview.java").toAbsolutePath().normalize();
        var javaCommand=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString();
        var process=new ProcessBuilder(javaCommand,"--class-path",System.getProperty("java.class.path"),source.toString(),"--replay",event.getEventId(),"regression","broker routing restored");
        process.environment().put("PAYMENT_DB_URL",((org.springframework.jdbc.datasource.DriverManagerDataSource)db.dataSource).getUrl());
        var running=process.redirectErrorStream(true).start();
        assertTrue(running.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));
        String output=new String(running.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,running.exitValue(),output);
        var replayed=events.selectPaymentSucceeded(payment);
        assertEquals("PENDING",replayed.getStatus());assertEquals(event.getEventId(),replayed.getEventId());
        assertEquals(1,replayed.getAttemptCount());assertEquals(0,replayed.getCycleAttemptCount());
        assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_outbox_redrive WHERE event_id=?",Integer.class,event.getEventId()));
        assertEquals(1,events.claim(event.getId(),"complete-test"));assertEquals(1,events.published(event.getId(),"complete-test"));
        var again=process.start();assertTrue(again.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));assertNotEquals(0,again.exitValue());
        assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_outbox_redrive WHERE event_id=?",Integer.class,event.getEventId()));
    }
}
