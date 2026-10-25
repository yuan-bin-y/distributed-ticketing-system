package com.byy.ticket.inventory.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.byy.ticket.inventory.admission.*;
import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import com.byy.ticket.inventory.service.impl.InventoryServiceImpl;
import com.byy.ticket.testing.MysqlFixture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

/** 真实 Redis + MySQL，覆盖代次丢失、最后一张竞争、取消墓碑及两份账本恢复。 */
@EnabledIfSystemProperty(named = "ticket.test.prehold", matches = "true")
class StockAdmissionIntegrationTest {
    static MysqlFixture db;
    static AnnotationConfigApplicationContext context;
    static LettuceConnectionFactory connection;
    static StockAdmissionService admission;
    static InventoryService inventory;
    static JdbcTemplate sql;
    static StringRedisTemplate redis;
    static final String PREFIX = "ticket:test:prehold:" + UUID.randomUUID() + ":";

    @Configuration
    @EnableTransactionManagement
    @MapperScan({"com.byy.ticket.inventory.mapper", "com.byy.ticket.inventory.admission"})
    @Import({InventoryServiceImpl.class, com.byy.ticket.inventory.seat.SeatInventoryService.class})
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
        JdbcTemplate jdbc() {
            return new JdbcTemplate(db.dataSource);
        }

        @Bean
        Clock clock() {
            return Clock.system(ZoneOffset.ofHours(8));
        }

        @Bean
        HotStockGate gate() {
            return new HotStockGate(
                    mock(StringRedisTemplate.class),
                    new SimpleMeterRegistry(),
                    false,
                    "test:",
                    2,
                    15000);
        }

        @Bean
        StockAdmissionService admissions(
                com.byy.ticket.inventory.mapper.TicketStockMapper stocks,
                AdmissionMapper mapper,
                PlatformTransactionManager manager,
                Clock clock,
                com.byy.ticket.inventory.seat.SeatInventoryService seats) {
            return new StockAdmissionService(
                    stocks,
                    mapper,
                    new RedisStockLedger(redis, PREFIX),
                    manager,
                    clock,
                    new SimpleMeterRegistry(),
                    true,
                    seats);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        db = new MysqlFixture();
        connection =
                new LettuceConnectionFactory(
                        "127.0.0.1", Integer.getInteger("ticket.test.redis.port", 16379));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        context = new AnnotationConfigApplicationContext(Config.class);
        admission = context.getBean(StockAdmissionService.class);
        inventory = context.getBean(InventoryService.class);
        sql = new JdbcTemplate(db.dataSource);
    }

    @AfterAll
    static void stop() throws Exception {
        if (redis != null)
            for (long tier = 101; tier <= 120; tier++)
                redis.delete(
                        List.of(
                                PREFIX + "{" + tier + "}:meta",
                                PREFIX + "{" + tier + "}:holds",
                                PREFIX + "{" + tier + "}:seats"));
        if (context != null) context.close();
        if (connection != null) connection.destroy();
        if (db != null) db.close();
    }

    void stock(long tier, int count) {
        sql.update(
                "INSERT INTO"
                    + " t_ticket_stock(ticket_tier_id,session_id,total_quantity,available_quantity)"
                    + " VALUES(?,1,?,?)",
                tier,
                count,
                count);
        admission.rebuild(tier);
    }

    ReserveStockDTO request(long tier, String id) {
        return new ReserveStockDTO(
                id,
                1L,
                tier,
                1,
                LocalDateTime.now(ZoneOffset.ofHours(8))
                        .plusMinutes(10)
                        .truncatedTo(ChronoUnit.MILLIS));
    }

    int available(long tier) {
        return Integer.parseInt(
                (String) redis.opsForHash().get(PREFIX + "{" + tier + "}:meta", "available"));
    }

    @Test
    void fiftyRequestsCompeteForTenTicketsAcrossTwoStages() throws Exception {
        stock(101, 10);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                var request = request(101, "burst_" + i);
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        admission.hold(request);
                                        inventory.reserve(request);
                                        return true;
                                    } catch (
                                            com.byy.ticket.inventory.exception
                                                            .InventoryConflictException
                                                    rejected) {
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) accepted++;
            assertEquals(10, accepted);
            assertEquals(
                    10,
                    sql.queryForObject(
                            "SELECT reserved_quantity FROM t_ticket_stock WHERE ticket_tier_id=101",
                            Integer.class));
            admission.rebuild(101);
            assertEquals(0, available(101));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateAndLostResponseDoNotDeductTwice() {
        stock(102, 3);
        var request = request(102, "duplicate");
        admission.hold(request);
        admission.hold(request);
        assertEquals(2, available(102));
        var first = inventory.reserve(request);
        assertEquals(first.reservationId(), inventory.reserve(request).reservationId());
        admission.rebuild(102);
        assertEquals(2, available(102));
        assertEquals("APPLIED", admission.hold(request).status());
        assertEquals(2, available(102));
        inventory.release(first.reservationId());
        inventory.release(first.reservationId());
        admission.rebuild(102);
        assertEquals(3, available(102));
    }

    @Test
    void cancelledBeforeReserveCannotBeReopened() {
        stock(103, 1);
        var request = request(103, "cancel_first");
        admission.cancel(request);
        assertThrows(RuntimeException.class, () -> admission.hold(request));
        assertThrows(RuntimeException.class, () -> inventory.reserve(request));
        admission.rebuild(103);
        assertEquals(1, available(103));
        assertEquals(
                0,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id=103",
                        Integer.class));
    }

    @Test
    void redisLossRebuildSubtractsDurablePendingHolds() {
        stock(104, 2);
        var request = request(104, "redis_loss");
        admission.hold(request);
        redis.delete(List.of(PREFIX + "{104}:meta", PREFIX + "{104}:holds"));
        admission.rebuild(104);
        assertEquals(1, available(104));
        inventory.reserve(request);
        admission.rebuild(104);
        assertEquals(1, available(104));
    }

    @Test
    void oldEpochCannotCreateIntentAfterRebuild() {
        stock(105, 1);
        var old = request(105, "old_epoch");
        var ledger = new RedisStockLedger(redis, PREFIX);
        String first = ledger.hold(105, "orphan", 1, "orphan");
        admission.rebuild(105);
        assertEquals(1, available(105));
        assertNotEquals(
                first.split("\\|")[0],
                ledger.hold(
                                105,
                                old.orderId(),
                                1,
                                RedisStockLedger.fingerprint(
                                        new StockAdmission(
                                                old.orderId(),
                                                105L,
                                                1L,
                                                1,
                                                old.expiresAt(),
                                                "HELD")))
                        .split("\\|")[0]);
    }

    @Test
    void lostRedisAcknowledgementCannotOverAllocateDurableIntent() {
        stock(106, 1);
        admission.hold(request(106, "already_held"));
        // 模拟副本切换遗失一次已确认 Redis 扣减，MySQL 准入总账仍应拒绝第二位用户。
        redis.opsForHash().put(PREFIX + "{106}:meta", "available", "1");
        assertThrows(
                RuntimeException.class, () -> admission.hold(request(106, "new_after_failover")));
        assertEquals(
                1,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM t_stock_admission WHERE ticket_tier_id=106 AND"
                            + " status='HELD'",
                        Integer.class));
        assertEquals(0, available(106));
    }

    @Test
    void retryUsesFirstExpiryEvenWhenCallerRecomputesIt() {
        stock(107, 1);
        var first = request(107, "retry_expiry");
        var saved = admission.hold(first);
        var later =
                new ReserveStockDTO(first.orderId(), 1L, 107L, 1, first.expiresAt().plusMinutes(1));
        assertEquals(saved.expiresAt(), admission.hold(later).expiresAt());
        assertThrows(RuntimeException.class, () -> inventory.reserve(later));
        assertEquals("RESERVED", inventory.reserve(first).status());
    }

    void seatStock(long tier) {
        stock(tier, 4);
        var regions = List.of(new com.byy.ticket.common.seat.SeatGrid.Region("A", 2, 2));
        context.getBean(com.byy.ticket.inventory.seat.SeatInventoryService.class)
                .initialize(
                        tier,
                        new com.byy.ticket.inventory.seat.SeatInventoryService.Layout(
                                tier,
                                1,
                                com.byy.ticket.common.seat.SeatGrid.hash(regions),
                                regions));
        admission.rebuild(tier);
    }

    ReserveStockDTO selected(long tier, String id, String... seats) {
        var base = request(tier, id);
        return new ReserveStockDTO(
                base.orderId(), 1L, tier, seats.length, base.expiresAt(), List.of(seats));
    }

    @Test
    void twoUsersCannotHoldSameSeatEvenWithEnoughTotalQuantity() throws Exception {
        seatStock(111);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var left =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    admission.hold(selected(111, "seat_left", "A:1:1"));
                                    return true;
                                } catch (RuntimeException rejected) {
                                    return false;
                                }
                            });
            var right =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    admission.hold(selected(111, "seat_right", "A:1:1"));
                                    return true;
                                } catch (RuntimeException rejected) {
                                    return false;
                                }
                            });
            start.countDown();
            assertNotEquals(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    sql.queryForObject(
                            "SELECT COUNT(*) FROM t_session_seat WHERE ticket_tier_id=111 AND"
                                + " status='HELD'",
                            Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void partialSelectionConflictRollsBackEverySeatAndQuantity() {
        seatStock(112);
        admission.hold(selected(112, "seat_owner", "A:1:1"));
        assertThrows(
                RuntimeException.class,
                () -> admission.hold(selected(112, "seat_conflict", "A:1:1", "A:1:2")));
        assertEquals(
                "AVAILABLE",
                sql.queryForObject(
                        "SELECT status FROM t_session_seat WHERE ticket_tier_id=112 AND"
                            + " seat_id='A:1:2'",
                        String.class));
        assertEquals(3, available(112));
    }

    @Test
    void selectedSeatsAreReleasedOrSoldWithCountsAndCannotBeReassignedAfterSale() {
        seatStock(113);
        var request = selected(113, "seat_sale", "A:1:2", "A:1:1");
        admission.hold(request);
        admission.hold(
                new ReserveStockDTO(
                        request.orderId(),
                        1L,
                        113L,
                        2,
                        request.expiresAt(),
                        List.of("A:1:1", "A:1:2")));
        var reservation = inventory.reserve(request);
        inventory.confirm(reservation.reservationId());
        inventory.confirm(reservation.reservationId());
        admission.rebuild(113);
        assertEquals(
                2,
                sql.queryForObject(
                        "SELECT sold_quantity FROM t_ticket_stock WHERE ticket_tier_id=113",
                        Integer.class));
        assertEquals(
                2,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM t_session_seat WHERE ticket_tier_id=113 AND"
                            + " status='SOLD'",
                        Integer.class));
        assertThrows(
                RuntimeException.class,
                () -> admission.hold(selected(113, "seat_resell", "A:1:1")));
        assertEquals(2, available(113));
    }

    @Test
    void cancelAndExpiryReleaseSeatsAndRedisLossKeepsHeldSeatsUnavailable() {
        seatStock(114);
        var request = selected(114, "seat_expire", "A:1:1");
        admission.hold(request);
        admission.rebuild(114);
        assertThrows(
                RuntimeException.class, () -> admission.hold(selected(114, "seat_other", "A:1:1")));
        sql.update(
                "UPDATE t_stock_admission SET"
                    + " expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE"
                    + " order_no='seat_expire'");
        admission.rebuild(114);
        assertEquals(4, available(114));
        var second = selected(114, "seat_cancel", "A:1:1");
        admission.hold(second);
        admission.cancel(second);
        admission.cancel(second);
        assertEquals(4, available(114));
        assertEquals(
                4,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM t_session_seat WHERE ticket_tier_id=114 AND"
                            + " status='AVAILABLE'",
                        Integer.class));
    }

    @Test
    void seatChangesPageSameVersionWithoutDroppingRows() {
        seatStock(115);
        var seats = context.getBean(com.byy.ticket.inventory.seat.SeatInventoryService.class);
        var first = seats.changes(115, "A", 1, 2, 0, 0, 0, 1);
        assertTrue(first.hasMore());
        var second = seats.changes(115, "A", 1, 2, 0, first.version(), first.nextAfter(), 10);
        assertEquals(3, second.records().size());
        assertFalse(second.hasMore());
        var request = selected(115, "seat_delta", "A:1:1", "A:1:2");
        admission.hold(request);
        var delta = seats.changes(115, "A", 1, 2, first.version(), 0, 0, 10);
        assertEquals(2, delta.records().size());
        assertTrue(delta.records().stream().allMatch(v -> v.status().equals("HELD")));
        admission.cancel(request);
        var released = seats.changes(115, "A", 1, 2, delta.version(), 0, 0, 10);
        assertEquals(2, released.records().size());
        assertTrue(released.records().stream().allMatch(v -> v.status().equals("AVAILABLE")));
    }

    @Test
    void existingReservationFromBeforeUpgradeDoesNotRequireAnotherPrehold() {
        stock(116, 1);
        var request = request(116, "legacy_committed");
        String id = "1".repeat(32);
        sql.update(
                "INSERT INTO"
                    + " t_stock_reservation(reservation_id,order_id,session_id,ticket_tier_id,quantity,expires_at,status)"
                    + " VALUES(?,?,1,116,1,?,'RESERVED')",
                id,
                request.orderId(),
                request.expiresAt());
        sql.update(
                "UPDATE t_ticket_stock SET available_quantity=0,reserved_quantity=1 WHERE"
                    + " ticket_tier_id=116");
        admission.rebuild(116);
        assertEquals(id, inventory.reserve(request).reservationId());
        assertEquals(
                0,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM t_stock_admission WHERE ticket_tier_id=116",
                        Integer.class));
    }

    @Test
    void historyIsNotRebuiltAndArchivedReceiptStillReturnsOriginalSale() {
        stock(117, 400);
        var expiry =
                LocalDateTime.now(ZoneOffset.ofHours(8))
                        .minusDays(9)
                        .truncatedTo(ChronoUnit.MILLIS);
        for (int index = 0; index < 300; index++) {
            String id = "history_" + index;
            sql.update(
                    "INSERT INTO"
                        + " t_stock_admission(order_no,ticket_tier_id,session_id,quantity,expires_at,status,seat_fingerprint)"
                        + " VALUES(?,117,1,1,?,'APPLIED','')",
                    id,
                    expiry);
            sql.update(
                    "INSERT INTO"
                        + " t_stock_reservation(reservation_id,order_id,ticket_tier_id,session_id,quantity,expires_at,status)"
                        + " VALUES(?,?,117,1,1,?,'SOLD')",
                    UUID.randomUUID().toString().replace("-", ""),
                    id,
                    expiry);
        }
        sql.update(
                "UPDATE t_ticket_stock SET available_quantity=100,sold_quantity=300 WHERE"
                    + " ticket_tier_id=117");
        admission.rebuild(117);
        assertEquals(0L, redis.opsForHash().size(PREFIX + "{117}:holds"));
        assertEquals(100, available(117));
        assertEquals(200, admission.archive(7));
        assertEquals(100, admission.archive(7));
        assertEquals(
                0,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM t_stock_admission WHERE ticket_tier_id=117",
                        Integer.class));
        var request = new ReserveStockDTO("history_0", 1L, 117L, 1, expiry);
        assertEquals("APPLIED", admission.hold(request).status());
        assertEquals("SOLD", inventory.reserve(request).status());
        assertEquals(100, available(117));
    }

    @Test
    void archiveKeepsReservedFactsAndCancellationTombstonesRemainEffective() {
        stock(118, 2);
        var reserved = request(118, "archive_reserved");
        admission.hold(reserved);
        inventory.reserve(reserved);
        var cancelled = request(118, "archive_cancelled");
        admission.hold(cancelled);
        admission.cancel(cancelled);
        sql.update(
                "UPDATE t_stock_admission SET expires_at=TIMESTAMPADD(DAY,-9,CURRENT_TIMESTAMP(3))"
                    + " WHERE ticket_tier_id=118");
        admission.archive(7);
        assertEquals(
                1,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM t_stock_admission WHERE order_no='archive_reserved'",
                        Integer.class));
        assertEquals(
                1,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM t_stock_admission_receipt WHERE"
                            + " order_no='archive_cancelled'",
                        Integer.class));
        assertThrows(RuntimeException.class, () -> admission.hold(cancelled));
        assertEquals("CANCELLED", admission.cancel(cancelled).status());
    }

    @Test
    void stagingRebuildLoadsActiveRowsInSmallBatches() {
        stock(119, 600);
        var expires =
                LocalDateTime.now(ZoneOffset.ofHours(8))
                        .plusMinutes(5)
                        .truncatedTo(ChronoUnit.MILLIS);
        for (int index = 0; index < 300; index++)
            sql.update(
                    "INSERT INTO"
                        + " t_stock_admission(order_no,ticket_tier_id,session_id,quantity,expires_at,status,seat_fingerprint)"
                        + " VALUES(?,119,1,1,?,'HELD','')",
                    "batch_" + index,
                    expires);
        admission.rebuild(119);
        assertEquals(300, available(119));
        assertEquals(300L, redis.opsForHash().size(PREFIX + "{119}:holds"));
        assertTrue(redis.keys(PREFIX + "{119}:build:*").isEmpty());
    }
}
