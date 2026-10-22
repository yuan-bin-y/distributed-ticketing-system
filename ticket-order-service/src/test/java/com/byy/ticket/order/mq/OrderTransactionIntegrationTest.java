package com.byy.ticket.order.mq;

import com.byy.ticket.testing.MysqlFixture;
import com.byy.ticket.order.service.*;
import com.byy.ticket.order.mapper.OrderMapper;
import com.byy.ticket.order.model.TicketOrder;
import com.byy.ticket.order.client.PaymentClient;
import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.observability.TraceSupport;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfSystemProperty(named="ticket.test.mysql",matches="true")
class OrderTransactionIntegrationTest {
    static MysqlFixture db;static AnnotationConfigApplicationContext context;static JdbcTemplate sql;
    static final AtomicLong ids=new AtomicLong(1);
    @Configuration @EnableTransactionManagement @MapperScan("com.byy.ticket.order.mapper")
    @Import({OrderTransactionService.class,OrderPaymentReceiptTransaction.class,PaymentMessageTransaction.class})
    static class Config {
        @Bean DataSource dataSource(){return db.dataSource;}
        @Bean SqlSessionFactory sqlSessionFactory() throws Exception{return db.factory();}
        @Bean PlatformTransactionManager transactions(){return new DataSourceTransactionManager(db.dataSource);}
        @Bean ConsumedEventMapper consumed(SqlSessionFactory factory){factory.getConfiguration().addMapper(ConsumedEventMapper.class);return new org.mybatis.spring.SqlSessionTemplate(factory).getMapper(ConsumedEventMapper.class);}
        @Bean TraceSupport traces(){var trace=mock(TraceSupport.class,RETURNS_DEEP_STUBS);when(trace.capture()).thenReturn(new TraceSupport.Snapshot(null,null));return trace;}
    }
    @BeforeAll static void start() throws Exception {
        db=new MysqlFixture();
        try {
            // 消费Mapper与业务Mapper处在不同包，显式注册同一个事务会话工厂。
            context=new AnnotationConfigApplicationContext();context.register(Config.class);
            context.refresh();sql=new JdbcTemplate(db.dataSource);
        }catch(Exception e){db.close();throw e;}
    }
    @AfterAll static void close() throws Exception {if(context!=null)context.close();if(db!=null)db.close();}
    TicketOrder order() {
        long id=ids.getAndIncrement();LocalDateTime now=LocalDateTime.now(ZoneOffset.ofHours(8)).truncatedTo(ChronoUnit.MILLIS);
        var rule=new TicketPurchaseRuleResponse(id,id,id,"test",BigDecimal.ONE,now.minusHours(1),now.plusHours(1),2);
        return context.getBean(OrderTransactionService.class).create(id,new OrderCreateDTO(id,1,UUID.randomUUID().toString()),rule,BigDecimal.ONE,now,now.plusMinutes(10));
    }
    PaymentResponse fact(TicketOrder order) {
        return new PaymentResponse(order.getOrderNo(),order.getOrderNo(),order.getUserId(),order.getTotalAmount(),"SUCCESS",order.getExpiresAt(),order.getExpiresAt().minusMinutes(1),"PENDING",null,null,order.getExpiresAt().minusMinutes(10));
    }
    @Test void snapshotFailureRollsBackOrderAndQuota() {
        int before=sql.queryForObject("SELECT COUNT(*) FROM t_order",Integer.class);
        sql.execute("CREATE TRIGGER fail_snapshot BEFORE INSERT ON t_order_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected snapshot failure'");
        try {assertThrows(RuntimeException.class,this::order);}finally{sql.execute("DROP TRIGGER fail_snapshot");}
        assertEquals(before,sql.queryForObject("SELECT COUNT(*) FROM t_order",Integer.class));
    }
    @Test void expiredTaskCannotOverwriteNewOwnerOrPaymentProgress() {
        var order=order();var mapper=context.getBean(OrderMapper.class);
        assertEquals(1,mapper.claim(order.getId(),"old",1000000));
        sql.update("UPDATE t_order SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",order.getId());
        assertEquals(1,mapper.claim(order.getId(),"new",10000000));
        assertEquals(0,mapper.finish(order.getId(),"old","STOCK_PENDING","CREATE_FAILED",null,order.getExpiresAt(),null));
        sql.update("UPDATE t_order SET status='PENDING_PAYMENT',reservation_id=? WHERE id=?","b".repeat(32),order.getId());
        context.getBean(OrderPaymentReceiptTransaction.class).accept(fact(order));
        assertEquals(0,mapper.finish(order.getId(),"new","PENDING_PAYMENT","CLOSED",null,order.getExpiresAt(),null));
        assertEquals("PAYMENT_CONFIRMING",mapper.selectById(order.getId()).getStatus());
    }
    @Test void commitThenAckFailureRedeliversWithoutDuplicateReceipt() throws Exception {
        var order=order();sql.update("UPDATE t_order SET status='PENDING_PAYMENT',reservation_id=? WHERE id=?","c".repeat(32),order.getId());
        var payment=mock(PaymentClient.class);when(payment.getByOrder(order.getOrderNo())).thenReturn(fact(order));
        var trace=context.getBean(TraceSupport.class);var metrics=new SimpleMeterRegistry();
        try {
            var scope=mock(TraceSupport.Scope.class);
            when(trace.resume(any(TraceSupport.Snapshot.class),anyString(),any(io.micrometer.tracing.Span.Kind.class))).thenReturn(scope);
            doAnswer(call->{Throwable failure=call.getArgument(0);if(!(failure instanceof java.io.IOException))throw new AssertionError("消费不应在ACK之前失败",failure);return null;}).when(scope).error(any());
            var consumer=new PaymentMessageConsumer(new ObjectMapper(),payment,context.getBean(PaymentMessageTransaction.class),mock(RabbitTemplate.class),"test",trace,new PaymentForwardingBackoff(Duration.ofMillis(1),Duration.ofMillis(10),metrics));
            var event=new PaymentSucceededMessage("d".repeat(32),"PAYMENT_SUCCEEDED",1,order.getOrderNo(),fact(order).paymentNo());
            var props=new MessageProperties();props.setDeliveryTag(1L);props.setMessageId(event.eventId());props.setConsumerQueue("test");
            var message=new Message(new ObjectMapper().writeValueAsBytes(event),props);var channel=mock(Channel.class);
            doThrow(new java.io.IOException("simulated crash before broker ACK")).doNothing().when(channel).basicAck(1L,false);
            assertThrows(java.io.IOException.class,()->consumer.consume(message,channel));
            assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_consumed_event WHERE event_id=?",Integer.class,event.eventId()));
            props.setRedelivered(true);consumer.consume(message,channel);
            assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM t_consumed_event WHERE event_id=?",Integer.class,event.eventId()));
            assertEquals("PAYMENT_CONFIRMING",context.getBean(OrderMapper.class).selectById(order.getId()).getStatus());
            verify(channel,times(2)).basicAck(1L,false);verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
        }finally{metrics.close();}
    }
    @Test void failedReceiptTransactionRollsBackConsumerMarker() {
        var order=order();sql.update("UPDATE t_order SET status='PENDING_PAYMENT',reservation_id=? WHERE id=?","e".repeat(32),order.getId());
        var event=new PaymentSucceededMessage("f".repeat(32),"PAYMENT_SUCCEEDED",1,order.getOrderNo(),fact(order).paymentNo());
        sql.execute("CREATE TRIGGER fail_receipt BEFORE UPDATE ON t_order FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected receipt failure'");
        try {assertThrows(RuntimeException.class,()->context.getBean(PaymentMessageTransaction.class).accept(event,"1".repeat(64),fact(order)));}
        finally {sql.execute("DROP TRIGGER fail_receipt");}
        assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM t_consumed_event WHERE event_id=?",Integer.class,event.eventId()));
        assertNull(context.getBean(OrderMapper.class).selectById(order.getId()).getPaymentNo());
    }
    @Test void unavailableInventoryNeverMarksOrderSuccessful() {
        var order=order();var mapper=context.getBean(OrderMapper.class);
        var inventory=mock(com.byy.ticket.order.client.InventoryClient.class);
        when(inventory.reserve(any())).thenThrow(new com.byy.ticket.order.client.exception.InventoryServiceCallException(
                com.byy.ticket.order.client.exception.InventoryServiceCallException.Reason.UNAVAILABLE,"simulated outage"));
        var trace=mock(TraceSupport.class,RETURNS_DEEP_STUBS);
        var workflow=new OrderStockWorkflow(mapper,context.getBean(com.byy.ticket.order.mapper.OrderItemMapper.class),inventory,
                new com.byy.ticket.order.config.OrderWorkflowProperties(false,null,false,null,null,null,null),Clock.system(ZoneOffset.ofHours(8)),
                mock(OrderPaymentFulfillment.class),mock(OrderTicketIssueTransaction.class),context.getBean(OrderTransactionService.class),trace);
        for(int i=0;i<2;i++) {
            sql.update("UPDATE t_order SET next_attempt_at=CURRENT_TIMESTAMP(3) WHERE id=?",order.getId());workflow.advance(order.getId());
            var current=mapper.selectById(order.getId());assertEquals("STOCK_PENDING",current.getStatus());assertNull(current.getReservationId());
        }
        verify(inventory,times(2)).reserve(any());
    }
    @Test void concurrentCloseAndPaymentInvalidateOldClosingTask() throws Exception {
        var order=order();var mapper=context.getBean(OrderMapper.class);
        sql.update("UPDATE t_order SET status='PENDING_PAYMENT',reservation_id=? WHERE id=?","a".repeat(32),order.getId());
        assertEquals(1,mapper.claim(order.getId(),"closing",10000000));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var close=pool.submit(()->{start.await();return mapper.beginClosing(order.getId(),"closing","PENDING_PAYMENT","a".repeat(32));});
            var pay=pool.submit(()->{start.await();return context.getBean(OrderPaymentReceiptTransaction.class).accept(fact(order));});
            start.countDown();close.get(10,java.util.concurrent.TimeUnit.SECONDS);pay.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("PAYMENT_CONFIRMING",mapper.selectById(order.getId()).getStatus());
            assertEquals(0,mapper.finish(order.getId(),"closing","CLOSING","CLOSED",null,order.getExpiresAt(),null));
        }finally{pool.shutdownNow();}
    }
}
