package com.byy.ticket.order.purchase;

import com.byy.ticket.testing.MysqlFixture;
import com.byy.ticket.order.mapper.*;
import com.byy.ticket.order.service.*;
import com.byy.ticket.order.client.StockAdmissionClient;
import com.byy.ticket.order.client.dto.TicketPurchaseRuleResponse;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.rules.PurchaseRuleEngine;
import com.byy.ticket.observability.TraceSupport;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
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
import static org.mockito.ArgumentMatchers.*;

@EnabledIfSystemProperty(named="ticket.test.mysql",matches="true")
class AsyncPurchaseIntegrationTest {
    static MysqlFixture db;static AnnotationConfigApplicationContext context;static JdbcTemplate sql;static PurchaseAcceptanceTransaction tx;static PurchaseAcceptanceMapper accepted;static AsyncPurchaseWorker worker;
    @Configuration @EnableTransactionManagement @MapperScan({"com.byy.ticket.order.mapper","com.byy.ticket.order.purchase"})
    @Import({PurchaseAcceptanceTransaction.class,OrderTransactionService.class,AsyncPurchaseWorker.class})
    static class Config {
        @Bean DataSource dataSource(){return db.dataSource;} @Bean SqlSessionFactory factory()throws Exception{return db.factory();}
        @Bean PlatformTransactionManager transactions(){return new DataSourceTransactionManager(db.dataSource);}
        @Bean ObjectMapper json(){return new ObjectMapper();} @Bean Clock clock(){return Clock.system(ZoneOffset.ofHours(8));}
        @Bean PurchaseRuleEngine rules(){return new PurchaseRuleEngine();}
        @Bean StockAdmissionClient inventory(){return mock(StockAdmissionClient.class);}
        @Bean TraceSupport traces(){var trace=mock(TraceSupport.class,RETURNS_DEEP_STUBS);when(trace.capture()).thenReturn(new TraceSupport.Snapshot(null,null));return trace;}
    }
    @BeforeAll static void start()throws Exception{db=new MysqlFixture();context=new AnnotationConfigApplicationContext(Config.class);sql=new JdbcTemplate(db.dataSource);tx=context.getBean(PurchaseAcceptanceTransaction.class);accepted=context.getBean(PurchaseAcceptanceMapper.class);worker=context.getBean(AsyncPurchaseWorker.class);}
    @AfterAll static void stop()throws Exception{if(context!=null)context.close();if(db!=null)db.close();}
    PurchaseAcceptance candidate(long user,String key){
        var now=LocalDateTime.now(ZoneOffset.ofHours(8)).truncatedTo(ChronoUnit.MILLIS);var request=new OrderCreateDTO(1L,1,key);
        var rule=new TicketPurchaseRuleResponse(1L,1L,1L,"async",BigDecimal.ONE,now.minusHours(1),now.plusHours(1),2,3L,2,2);
        var json=new ObjectMapper();var result=new PurchaseAcceptance();result.setRequestId(PurchaseRequestId.of(user,key));result.setUserId(user);result.setIdempotencyKey(key);result.setTicketTierId(1L);result.setQuantity(1);
        result.setRequestJson(json.writeValueAsString(request));result.setRuleJson(json.writeValueAsString(rule));result.setAmount(BigDecimal.ONE);result.setAcceptedAt(now);result.setExpiresAt(now.plusMinutes(10));result.setNextAttemptAt(now);result.setAttemptCount(0);result.setStatus("ACCEPTED");return result;
    }
    @Test void acceptanceAndOutboxRollbackTogether(){
        var candidate=candidate(1,"atomic");sql.execute("CREATE TRIGGER fail_outbox BEFORE INSERT ON t_order_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected outbox failure'");
        try{assertThrows(RuntimeException.class,()->tx.accept(candidate));}finally{sql.execute("DROP TRIGGER fail_outbox");}
        assertNull(accepted.selectById(candidate.getRequestId()));
    }
    @Test void duplicateDeliveryAndSyncAsyncRaceDoNotOccupyQuotaTwice(){
        var item=tx.accept(candidate(2,"duplicate"));tx.accept(candidate(2,"duplicate"));worker.advance(item.getRequestId());worker.advance(item.getRequestId());
        assertEquals("CREATED",accepted.selectById(item.getRequestId()).getStatus());
        assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_order WHERE user_id=2",Integer.class));
        assertEquals(1L,sql.queryForObject("SELECT occupied_quantity FROM t_user_session_quota WHERE user_id=2 AND session_id=1",Long.class));
        var json=new ObjectMapper();var order=context.getBean(OrderTransactionService.class).createAccepted(item.getRequestId(),2L,json.readValue(item.getRequestJson(),OrderCreateDTO.class),json.readValue(item.getRuleJson(),TicketPurchaseRuleResponse.class),BigDecimal.ONE,item.getAcceptedAt(),item.getExpiresAt());
        assertEquals(item.getRequestId(),order.getOrderNo());assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_order WHERE user_id=2",Integer.class));
    }
    @Test void quotaFailureBecomesDurableCancellationAndOnlyThenRejected(){
        worker.advance(tx.accept(candidate(3,"first")).getRequestId());worker.advance(tx.accept(candidate(3,"second")).getRequestId());
        var rejected=tx.accept(candidate(3,"third"));worker.advance(rejected.getRequestId());assertEquals("CANCELLING",accepted.selectById(rejected.getRequestId()).getStatus());
        worker.advance(rejected.getRequestId());assertEquals("REJECTED",accepted.selectById(rejected.getRequestId()).getStatus());verify(context.getBean(StockAdmissionClient.class)).cancel(any());
    }
    @Test void staleWorkerCannotCreateOrder(){
        var item=tx.accept(candidate(4,"stale"));assertEquals(1,accepted.claim(item.getRequestId(),"owner"));
        assertFalse(tx.create(item.getRequestId(),"old_owner",LocalDateTime.now(ZoneOffset.ofHours(8))));
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM t_order WHERE user_id=4",Integer.class));
    }
    @Test void asyncCannotReuseSyncKeyWithDifferentPurchaseParameters(){
        var item=candidate(7,"mixed_conflict");var json=new ObjectMapper();var rule=json.readValue(item.getRuleJson(),TicketPurchaseRuleResponse.class);
        context.getBean(OrderTransactionService.class).createAccepted(item.getRequestId(),7L,new OrderCreateDTO(1L,1,item.getIdempotencyKey()),rule,BigDecimal.ONE,item.getAcceptedAt(),item.getExpiresAt());
        item.setRequestJson(json.writeValueAsString(new OrderCreateDTO(1L,2,item.getIdempotencyKey())));item.setQuantity(2);
        assertThrows(com.byy.ticket.order.exception.OrderConflictException.class,()->tx.accept(item));assertNull(accepted.selectById(item.getRequestId()));
    }
    @Test void failedPublisherHasReviewStateAndAuditedReplayPreservesIdentity(){
        var item=tx.accept(candidate(5,"review"));String event=sql.queryForObject("SELECT event_id FROM t_order_outbox WHERE request_id=?",String.class,item.getRequestId());
        sql.update("UPDATE t_order_outbox SET status='REVIEW_REQUIRED',attempt_count=20 WHERE event_id=?",event);tx.replay(event,99,"修复交换机后重新投递");
        assertEquals("PENDING",sql.queryForObject("SELECT status FROM t_order_outbox WHERE event_id=?",String.class,event));
        assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_order_outbox_replay WHERE event_id=?",Integer.class,event));
    }
    @Test @EnabledIfSystemProperty(named="ticket.test.rabbitmq",matches="true")
    void realBrokerConfirmFailureRecoveryAndDuplicateConsumption()throws Exception{
        var connection=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1",Integer.getInteger("ticket.test.rabbitmq.port",15673));
        connection.setUsername(System.getenv().getOrDefault("TICKET_TEST_RABBITMQ_USER", "guest"));
        connection.setPassword(System.getenv().getOrDefault("TICKET_TEST_RABBITMQ_PASSWORD", "guest"));
        connection.setPublisherConfirmType(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);connection.setPublisherReturns(true);
        var rabbit=new org.springframework.amqp.rabbit.core.RabbitTemplate(connection);rabbit.setMandatory(true);
        var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(connection);String prefix="ticket-test-"+java.util.UUID.randomUUID();
        var json=new ObjectMapper();var outbox=context.getBean(PurchaseOutboxMapper.class);var item=tx.accept(candidate(6,"real_mq"));
        String event=sql.queryForObject("SELECT event_id FROM t_order_outbox WHERE request_id=?",String.class,item.getRequestId());
        var relay=new PurchaseMq.Relay(outbox,rabbit,json,prefix,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        try{
            // 交换机尚未创建，持久化受理和待发送事件不能丢失。
            relay.publish(event);assertEquals("PENDING",outbox.get(event).status());assertEquals("ACCEPTED",accepted.selectById(item.getRequestId()).getStatus());
            for(var declaration:new PurchaseMq().purchaseTopology(prefix).getDeclarables()){
                if(declaration instanceof org.springframework.amqp.core.Exchange exchange)admin.declareExchange(exchange);
                if(declaration instanceof org.springframework.amqp.core.Queue queue)admin.declareQueue(queue);
                if(declaration instanceof org.springframework.amqp.core.Binding binding)admin.declareBinding(binding);
            }
            sql.update("UPDATE t_order_outbox SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE event_id=?",event);relay.publish(event);assertEquals("PUBLISHED",outbox.get(event).status());
            var brokerConnection=connection.createConnection();var channel=brokerConnection.createChannel(false);
            try{
                var delivery=channel.basicGet(prefix+".order.purchase-requested",false);assertNotNull(delivery);
                var properties=new org.springframework.amqp.core.MessageProperties();properties.setMessageId(event);properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
                var message=new org.springframework.amqp.core.Message(delivery.getBody(),properties);
                var consumer=new PurchaseMq.Consumer(outbox,worker,json,rabbit,prefix);consumer.consume(message,channel);
                assertEquals("CREATED",accepted.selectById(item.getRequestId()).getStatus());
                // 同一事件再次投递也不会重复建单或占用额度。
                var duplicate=new org.springframework.amqp.core.MessageProperties();duplicate.setMessageId(event);
                var confirmation=new org.springframework.amqp.rabbit.connection.CorrelationData("duplicate");
                rabbit.send(prefix+".purchase.exchange","purchase.requested",new org.springframework.amqp.core.Message(delivery.getBody(),duplicate),confirmation);
                assertTrue(confirmation.getFuture().get(5,java.util.concurrent.TimeUnit.SECONDS).ack());
                var again=channel.basicGet(prefix+".order.purchase-requested",false);assertNotNull(again);properties.setDeliveryTag(again.getEnvelope().getDeliveryTag());consumer.consume(new org.springframework.amqp.core.Message(again.getBody(),properties),channel);
                assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_order WHERE user_id=6",Integer.class));
            }finally{channel.close();brokerConnection.close();}
        }finally{admin.deleteQueue(prefix+".order.purchase-requested");admin.deleteQueue(prefix+".order.purchase-dead");admin.deleteExchange(prefix+".purchase.exchange");connection.destroy();}
    }
}
