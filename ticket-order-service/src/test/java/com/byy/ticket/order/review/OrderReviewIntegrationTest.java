package com.byy.ticket.order.review;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.byy.ticket.observability.TraceSupport;
import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.mapper.*;
import com.byy.ticket.order.model.*;
import com.byy.ticket.order.rules.PurchaseRuleEngine;
import com.byy.ticket.order.service.OrderTransactionService;
import com.byy.ticket.testing.MysqlFixture;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import javax.sql.DataSource;

@EnabledIfSystemProperty(named = "ticket.test.mysql", matches = "true")
class OrderReviewIntegrationTest {
    static MysqlFixture db;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate sql;
    static OrderReviewTransaction reviews;
    static OrderReviewMapper actions;

    @Configuration
    @EnableTransactionManagement
    @MapperScan({"com.byy.ticket.order.mapper", "com.byy.ticket.order.review"})
    @Import({OrderTransactionService.class, OrderReviewTransaction.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return db.dataSource;
        }

        @Bean
        SqlSessionFactory factory() throws Exception {
            return db.factory();
        }

        @Bean
        PlatformTransactionManager transactions() {
            return new DataSourceTransactionManager(db.dataSource);
        }

        @Bean
        Clock clock() {
            return Clock.system(ZoneOffset.ofHours(8));
        }

        @Bean
        PurchaseRuleEngine rules() {
            return new PurchaseRuleEngine();
        }

        @Bean
        TraceSupport traces() {
            var trace = mock(TraceSupport.class);
            when(trace.capture()).thenReturn(new TraceSupport.Snapshot(null, null));
            return trace;
        }
    }

    @BeforeAll
    static void start() throws Exception {
        db = new MysqlFixture();
        context = new AnnotationConfigApplicationContext(Config.class);
        sql = new JdbcTemplate(db.dataSource);
        reviews = context.getBean(OrderReviewTransaction.class);
        actions = context.getBean(OrderReviewMapper.class);
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        if (db != null) db.close();
    }

    TicketOrder order() {
        long user = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000) + 1;
        var now = LocalDateTime.now(ZoneOffset.ofHours(8)).truncatedTo(ChronoUnit.MILLIS);
        var rule =
                new TicketPurchaseRuleResponse(
                        1L,
                        2L,
                        3L,
                        "review",
                        BigDecimal.ONE,
                        now.minusHours(1),
                        now.plusHours(1),
                        2);
        var value =
                context.getBean(OrderTransactionService.class)
                        .createAccepted(
                                UUID.randomUUID().toString().replace("-", ""),
                                user,
                                new OrderCreateDTO(3L, 1, UUID.randomUUID().toString()),
                                rule,
                                BigDecimal.ONE,
                                now,
                                now.plusMinutes(10));
        sql.update(
                "UPDATE t_order SET status='REVIEW_REQUIRED',last_error='test review' WHERE id=?",
                value.getId());
        return context.getBean(OrderMapper.class).selectById(value.getId());
    }

    OrderReviewMapper.Action action(TicketOrder order) {
        return reviews.submit(
                order.getOrderNo(),
                99,
                UUID.randomUUID().toString(),
                OrderReviewDecision.Intent.RECONCILE,
                "核对测试");
    }

    @Test
    void duplicateAdminCommandIsIdempotentAndRejectsChangedReason() {
        var order = order();
        var action = action(order);
        assertEquals(
                action.id(),
                reviews.submit(
                                order.getOrderNo(),
                                99,
                                action.requestKey(),
                                OrderReviewDecision.Intent.RECONCILE,
                                "核对测试")
                        .id());
        assertThrows(
                RuntimeException.class,
                () ->
                        reviews.submit(
                                order.getOrderNo(),
                                99,
                                action.requestKey(),
                                OrderReviewDecision.Intent.RECONCILE,
                                "不同原因"));
    }

    @Test
    void closingWithoutCancellationCannotReleaseQuota() {
        var order = order();
        var action = action(order);
        reviews.claim(action.id(), "a".repeat(32));
        assertThrows(
                RuntimeException.class,
                () ->
                        reviews.resolve(
                                action.id(),
                                "a".repeat(32),
                                OrderStatus.CLOSED,
                                new OrderReviewDecision.Facts(null, null),
                                "{}",
                                false));
        assertEquals(
                "HELD",
                context.getBean(OrderMapper.class).selectById(order.getId()).getQuotaStatus());
    }

    @Test
    void confirmedCancellationClosesAndReleasesQuotaOnceWithAudit() {
        var order = order();
        var action = action(order);
        String token = "a".repeat(32);
        reviews.claim(action.id(), token);
        reviews.resolve(
                action.id(),
                token,
                OrderStatus.CLOSED,
                new OrderReviewDecision.Facts(null, null),
                "{}",
                true);
        reviews.resolve(
                action.id(),
                token,
                OrderStatus.CLOSED,
                new OrderReviewDecision.Facts(null, null),
                "{}",
                true);
        var saved = context.getBean(OrderMapper.class).selectById(order.getId());
        assertEquals("CLOSED", saved.getStatus());
        assertEquals("RELEASED", saved.getQuotaStatus());
        assertEquals(
                0L,
                sql.queryForObject(
                        "SELECT occupied_quantity FROM t_user_session_quota WHERE user_id=? AND"
                            + " session_id=2",
                        Long.class,
                        order.getUserId()));
        assertEquals(
                0,
                sql.queryForObject(
                        "SELECT occupied_sessions FROM t_user_event_quota WHERE user_id=? AND"
                            + " event_id=1",
                        Integer.class,
                        order.getUserId()));
        assertEquals("DONE", actions.get(action.id()).status());
    }

    @Test
    void auditFailureRollsBackOrderAndQuota() {
        var order = order();
        var action = action(order);
        String token = "b".repeat(32);
        reviews.claim(action.id(), token);
        sql.execute(
                "CREATE TRIGGER fail_review BEFORE UPDATE ON t_order_review_action FOR EACH ROW"
                    + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected audit failure'");
        try {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            reviews.resolve(
                                    action.id(),
                                    token,
                                    OrderStatus.CLOSED,
                                    new OrderReviewDecision.Facts(null, null),
                                    "{}",
                                    true));
        } finally {
            sql.execute("DROP TRIGGER fail_review");
        }
        var saved = context.getBean(OrderMapper.class).selectById(order.getId());
        assertEquals("REVIEW_REQUIRED", saved.getStatus());
        assertEquals("HELD", saved.getQuotaStatus());
    }

    @Test
    void expiredWorkerCannotOverwriteNewCommandLease() {
        var order = order();
        var action = action(order);
        reviews.claim(action.id(), "a".repeat(32));
        sql.update(
                "UPDATE t_order SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE"
                    + " id=?",
                order.getId());
        sql.update(
                "UPDATE t_order_review_action SET"
                    + " next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE id=?",
                action.id());
        assertNotNull(reviews.claim(action.id(), "b".repeat(32)));
        reviews.resolve(
                action.id(),
                "a".repeat(32),
                OrderStatus.CLOSING,
                new OrderReviewDecision.Facts(null, null),
                "{}",
                false);
        assertEquals("RUNNING", actions.get(action.id()).status());
        assertEquals("b".repeat(32), actions.get(action.id()).leaseToken());
    }
}
