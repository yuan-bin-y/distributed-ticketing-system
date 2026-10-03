import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.inventory.TicketInventoryApplication;
import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import com.byy.ticket.inventory.exception.InventoryConflictException;
import com.byy.ticket.inventory.service.InventoryService;
import com.byy.ticket.inventory.vo.inventory.StockReservationVO;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Real MySQL verification; fixtures are committed for competing threads, then removed by exact IDs. */
public class InventoryVerification {
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final MutableClock TEST_CLOCK = new MutableClock();
    private final String runId = UUID.randomUUID().toString().replace("-", "");
    private final long baseId = 8_000_000_000_000L
            + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000_000L);
    private final List<Long> fixtureTierIds = new ArrayList<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(20);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger transientRetries = new AtomicInteger();
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final TransactionTemplate transactions;
    private final int port;
    private long missingTierId;
    private int passed;

    InventoryVerification(JdbcTemplate jdbc, InventoryService inventory,
                          PlatformTransactionManager manager, int port) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.transactions = new TransactionTemplate(manager);
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        var application = new SpringApplication(TicketInventoryApplication.class, VerificationClockConfiguration.class);
        try (var context = application.run(
                "--server.port=0", "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.discovery.enabled=false", "--spring.main.banner-mode=off",
                "--spring.datasource.hikari.maximum-pool-size=25",
                "--spring.datasource.hikari.connection-timeout=10000", "--logging.level.root=WARN")) {
            var verification = new InventoryVerification(context.getBean(JdbcTemplate.class),
                    context.getBean(InventoryService.class), context.getBean(PlatformTransactionManager.class),
                    ((WebServerApplicationContext) context).getWebServer().getPort());
            try {
                verification.run();
                System.out.println("PASS inventory verification: " + verification.passed
                        + " checks, transient database retries: " + verification.transientRetries.get());
            } finally {
                verification.cleanup();
            }
        }
    }

    void run() throws Exception {
        missingTierId = baseId + 100_000;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM t_ticket_stock WHERE ticket_tier_id = ?", Long.class, missingTierId) != 0) {
            missingTierId++;
        }
        noOverselling();
        System.out.println("PASS 50 concurrent purchases: 10 reserved, 40 rejected; no overselling");
        concurrentIdempotence();
        System.out.println("PASS 20 concurrent retries: one reservation; changed payloads rejected");
        competingPayloads();
        System.out.println("PASS concurrent different quantities with one order key: one winner, one conflict");
        terminalStateIdempotence();
        System.out.println("PASS repeated confirm/release and immutable terminal states");
        competingTerminalCommands();
        System.out.println("PASS 10 competing confirm/release rounds: exactly one winner each");
        reserveFailures();
        System.out.println("PASS missing stock, insufficient quantity, and session mismatch leave no reservation");
        expiredRetry();
        System.out.println("PASS expired retry remains idempotent; new expired request is rejected");
        transactionRollback();
        System.out.println("PASS reserve/confirm/release roll back counters and records together");
        failedTransitionRollback();
        System.out.println("PASS failed stock transition rolls its preceding reservation state update back");
        httpContract();
        System.out.println("PASS HTTP success, 400/404/409, Result envelope, and trace propagation");
    }

    void noOverselling() throws Exception {
        long tier = fixture(10);
        List<Callable<StockReservationVO>> requests = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ReserveStockDTO request = request("limited_" + i, tier, 1);
            requests.add(() -> {
                try { return inventory.reserve(request); }
                catch (InventoryConflictException insufficient) { return null; }
            });
        }
        List<StockReservationVO> results = race(requests);
        check(results.stream().filter(result -> result != null).count() == 10,
                "50 concurrent purchases reserve exactly 10 available tickets");
        stock(tier, 0, 10, 0);
        check(reservationCount(tier) == 10, "failed purchases leave no reservation rows");
        reconcile(tier);
    }

    void concurrentIdempotence() throws Exception {
        long tier = fixture(10);
        ReserveStockDTO request = request("same_order", tier, 2);
        List<Callable<StockReservationVO>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) requests.add(() -> inventory.reserve(request));
        var ids = new HashSet<String>();
        for (var result : race(requests)) ids.add(result.reservationId());
        check(ids.size() == 1, "20 concurrent retries return the same reservation ID");
        stock(tier, 8, 2, 0);
        check(reservationCount(tier) == 1, "idempotent retries insert one row");
        conflict(() -> inventory.reserve(new ReserveStockDTO(request.orderId(), request.sessionId(), tier,
                3, request.expiresAt())), "same order with changed quantity rejected");
        conflict(() -> inventory.reserve(new ReserveStockDTO(request.orderId(), request.sessionId(), tier,
                2, request.expiresAt().plusMinutes(1))), "same order with changed expiry rejected");
        conflict(() -> inventory.reserve(new ReserveStockDTO(request.orderId(), request.sessionId() + 1,
                tier, 2, request.expiresAt())), "same order with changed session rejected");
        stock(tier, 8, 2, 0);
        reconcile(tier);
    }

    void terminalStateIdempotence() {
        long soldTier = fixture(10);
        ReserveStockDTO request = request("sold", soldTier, 2);
        var reserved = inventory.reserve(request);
        check("SOLD".equals(inventory.confirm(reserved.reservationId()).status()), "confirm marks SOLD");
        check("SOLD".equals(inventory.confirm(reserved.reservationId()).status()), "repeat confirm succeeds");
        check("SOLD".equals(inventory.reserve(request).status()), "reserve retry preserves SOLD state");
        conflict(() -> inventory.release(reserved.reservationId()), "SOLD cannot become RELEASED");
        stock(soldTier, 8, 0, 2);
        reconcile(soldTier);

        long releasedTier = fixture(10);
        ReserveStockDTO releasedRequest = request("released", releasedTier, 3);
        var released = inventory.reserve(releasedRequest);
        check("RELEASED".equals(inventory.release(released.reservationId()).status()), "release marks RELEASED");
        check("RELEASED".equals(inventory.release(released.reservationId()).status()), "repeat release succeeds");
        check("RELEASED".equals(inventory.reserve(releasedRequest).status()), "reserve retry preserves RELEASED state");
        conflict(() -> inventory.confirm(released.reservationId()), "RELEASED cannot become SOLD");
        stock(releasedTier, 10, 0, 0);
        reconcile(releasedTier);
    }

    void competingPayloads() throws Exception {
        long tier = fixture(10);
        ReserveStockDTO first = request("different_payload", tier, 2);
        ReserveStockDTO second = new ReserveStockDTO(first.orderId(), first.sessionId(), tier, 3, first.expiresAt());
        List<Callable<StockReservationVO>> commands = List.of(
                () -> reserveOrConflict(first), () -> reserveOrConflict(second));
        List<StockReservationVO> results = race(commands);
        check(results.stream().filter(result -> result != null).count() == 1,
                "different concurrent payloads sharing one order key have exactly one winner");
        StockReservationVO winner = results.stream().filter(result -> result != null).findFirst().orElseThrow();
        check(winner.quantity() == 2 || winner.quantity() == 3, "winning payload quantity is retained");
        check(reservationCount(tier) == 1, "different concurrent payloads insert one reservation");
        stock(tier, 10 - winner.quantity(), winner.quantity(), 0);
        reconcile(tier);
        ReserveStockDTO loser = winner.quantity().equals(first.quantity()) ? second : first;
        http("POST", "/internal/stock-reservations", json(loser), 409, "CONFLICT");
        stock(tier, 10 - winner.quantity(), winner.quantity(), 0);
    }

    StockReservationVO reserveOrConflict(ReserveStockDTO request) {
        try { return inventory.reserve(request); }
        catch (InventoryConflictException expected) { return null; }
    }

    void competingTerminalCommands() throws Exception {
        for (int i = 0; i < 10; i++) {
            long tier = fixture(1);
            var reservation = inventory.reserve(request("terminal_race_" + i, tier, 1));
            List<Callable<String>> commands = List.of(
                    () -> terminal(() -> inventory.confirm(reservation.reservationId())),
                    () -> terminal(() -> inventory.release(reservation.reservationId())));
            List<String> results = race(commands);
            check(results.stream().filter(value -> !"CONFLICT".equals(value)).count() == 1,
                    "competing confirm/release has exactly one winner, round " + (i + 1));
            String status = jdbc.queryForObject("SELECT status FROM t_stock_reservation WHERE reservation_id = ?",
                    String.class, reservation.reservationId());
            if ("SOLD".equals(status)) stock(tier, 0, 0, 1);
            else {
                check("RELEASED".equals(status), "competing terminal result is valid");
                stock(tier, 1, 0, 0);
            }
            reconcile(tier);
        }
    }

    void reserveFailures() {
        long tier = fixture(2);
        ReserveStockDTO insufficient = request("insufficient", tier, 3);
        conflict(() -> inventory.reserve(insufficient), "insufficient stock rejected");
        noReservation(insufficient.orderId());
        ReserveStockDTO mismatch = request("wrong_session", tier, 1);
        mismatch = new ReserveStockDTO(mismatch.orderId(), mismatch.sessionId() + 1,
                tier, mismatch.quantity(), mismatch.expiresAt());
        final var mismatchRequest = mismatch;
        conflict(() -> inventory.reserve(mismatchRequest), "stock session mismatch rejected");
        noReservation(mismatch.orderId());
        ReserveStockDTO missing = request("missing", missingTierId, 1);
        expect(ResourceNotFoundException.class, () -> inventory.reserve(missing), "missing stock rejected");
        noReservation(missing.orderId());
        stock(tier, 2, 0, 0);
        reconcile(tier);
    }

    void transactionRollback() {
        long tier = fixture(5);
        ReserveStockDTO request = request("rollback_reserve", tier, 2);
        transactions.executeWithoutResult(transaction -> {
            inventory.reserve(request);
            transaction.setRollbackOnly();
        });
        stock(tier, 5, 0, 0);
        noReservation(request.orderId());
        var reservation = inventory.reserve(request("rollback_terminal", tier, 2));
        transactions.executeWithoutResult(transaction -> {
            inventory.confirm(reservation.reservationId());
            transaction.setRollbackOnly();
        });
        stock(tier, 3, 2, 0);
        status(reservation.reservationId(), "RESERVED");
        transactions.executeWithoutResult(transaction -> {
            inventory.release(reservation.reservationId());
            transaction.setRollbackOnly();
        });
        stock(tier, 3, 2, 0);
        status(reservation.reservationId(), "RESERVED");
        reconcile(tier);
        check(true, "stock counters and reservation state roll back together");
    }

    void expiredRetry() throws Exception {
        long tier = fixture(2);
        ReserveStockDTO request = request("expiry", tier, 1);
        var original = inventory.reserve(request);
        Instant before = TEST_CLOCK.instant();
        try {
            TEST_CLOCK.set(request.expiresAt().toInstant(ZoneOffset.ofHours(8)).plusMillis(1));
            var retried = inventory.reserve(request);
            check(original.reservationId().equals(retried.reservationId()) && "RESERVED".equals(retried.status()),
                    "original retry after expiry returns the same reservation without releasing stock");
            var expiredNewRequest = new ReserveStockDTO(order("expired_new"), request.sessionId(), tier,
                    1, request.expiresAt());
            expect(IllegalArgumentException.class, () -> inventory.reserve(expiredNewRequest),
                    "new reservation with expired deadline rejected");
            noReservation(expiredNewRequest.orderId());
            http("POST", "/internal/stock-reservations", json(expiredNewRequest), 400, "BAD_REQUEST");
            stock(tier, 1, 1, 0);
            reconcile(tier);
        } finally {
            TEST_CLOCK.set(before);
        }
    }

    void failedTransitionRollback() {
        long tier = fixture(5);
        var reservation = inventory.reserve(request("inconsistent_stock", tier, 2));
        // Only this run's fixture is changed. Keep the stock conservation CHECK valid,
        // but make reserved quantity too small for this reservation's transition.
        jdbc.update("UPDATE t_ticket_stock SET available_quantity = 4, reserved_quantity = 1 WHERE ticket_tier_id = ?", tier);
        try {
            expect(IllegalStateException.class, () -> inventory.confirm(reservation.reservationId()),
                    "confirm fails when the fixture stock counters cannot satisfy its reservation");
            status(reservation.reservationId(), "RESERVED");
            stock(tier, 4, 1, 0);
            expect(IllegalStateException.class, () -> inventory.release(reservation.reservationId()),
                    "release fails when the fixture stock counters cannot satisfy its reservation");
            status(reservation.reservationId(), "RESERVED");
            stock(tier, 4, 1, 0);
        } finally {
            jdbc.update("UPDATE t_ticket_stock SET available_quantity = 3, reserved_quantity = 2 WHERE ticket_tier_id = ?", tier);
        }
        reconcile(tier);
    }

    void httpContract() throws Exception {
        long tier = fixture(3);
        ReserveStockDTO request = request("http", tier, 2);
        http("POST", "/internal/stock-reservations", json(request), 200, "OK");
        var reservation = inventory.reserve(request);
        http("GET", "/internal/stocks/" + tier, null, 200, "OK");
        http("GET", "/internal/stock-reservations/" + reservation.reservationId(), null, 200, "OK");
        http("POST", "/internal/stock-reservations/" + reservation.reservationId() + "/confirm", null, 200, "OK");
        http("POST", "/internal/stock-reservations/" + reservation.reservationId() + "/confirm", null, 200, "OK");
        http("POST", "/internal/stock-reservations/" + reservation.reservationId() + "/release", null, 409, null);
        http("POST", "/internal/stock-reservations", json(request("http_insufficient", tier, 2)), 409, null);
        http("POST", "/internal/stock-reservations", "{}", 400, "BAD_REQUEST");
        http("POST", "/internal/stock-reservations", "malformed", 400, "BAD_REQUEST");
        http("POST", "/internal/stock-reservations", json(new ReserveStockDTO(order("invalid_quantity"),
                tier + 10_000, tier, 0, request.expiresAt())), 400, "BAD_REQUEST");
        http("POST", "/internal/stock-reservations", json(new ReserveStockDTO(order("submillisecond"),
                tier + 10_000, tier, 1, request.expiresAt().withNano(1))), 400, "BAD_REQUEST");
        http("GET", "/internal/stocks/" + missingTierId, null, 404, "RESOURCE_NOT_FOUND");
        String missingReservationId = UUID.randomUUID().toString().replace("-", "");
        http("GET", "/internal/stock-reservations/" + missingReservationId, null, 404, "RESOURCE_NOT_FOUND");
        http("POST", "/internal/stock-reservations/" + missingReservationId + "/confirm", null, 404, "RESOURCE_NOT_FOUND");
        stock(tier, 1, 0, 2);
        reconcile(tier);
    }

    long fixture(int total) {
        long tier = baseId + fixtureTierIds.size();
        // A collision aborts instead of touching the existing row.
        jdbc.update("INSERT INTO t_ticket_stock(ticket_tier_id, session_id, total_quantity, available_quantity, reserved_quantity, sold_quantity) VALUES (?, ?, ?, ?, 0, 0)",
                tier, tier + 10_000, total, total);
        fixtureTierIds.add(tier);
        return tier;
    }

    ReserveStockDTO request(String suffix, long tier, int quantity) {
        return new ReserveStockDTO(order(suffix), tier + 10_000, tier, quantity,
                LocalDateTime.now(TEST_CLOCK).plusHours(2).withNano(0));
    }

    String order(String suffix) { return "v_" + runId + "_" + suffix; }

    <T> List<T> race(List<Callable<T>> commands) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (var command : commands) futures.add(workers.submit(() -> {
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("race start timed out");
            return callWithRetry(command);
        }));
        start.countDown();
        List<T> values = new ArrayList<>();
        for (var future : futures) values.add(future.get(30, TimeUnit.SECONDS));
        return values;
    }

    <T> T callWithRetry(Callable<T> command) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { return command.call(); }
            catch (TransientDataAccessException retryable) {
                if (attempt >= 4) throw retryable;
                transientRetries.incrementAndGet();
                // Every command retries its original stable order/reservation ID.
                Thread.sleep(20L * (attempt + 1));
            }
        }
    }

    String terminal(Callable<StockReservationVO> action) throws Exception {
        try { return action.call().status(); }
        catch (InventoryConflictException expected) { return "CONFLICT"; }
    }

    void stock(long tier, int available, int reserved, int sold) {
        var row = jdbc.queryForMap("SELECT total_quantity, available_quantity, reserved_quantity, sold_quantity FROM t_ticket_stock WHERE ticket_tier_id = ?", tier);
        check(number(row.get("available_quantity")) == available
                        && number(row.get("reserved_quantity")) == reserved
                        && number(row.get("sold_quantity")) == sold
                        && number(row.get("total_quantity")) == available + reserved + sold,
                "stock counters satisfy expected values and conservation");
    }

    void reconcile(long tier) {
        var stock = jdbc.queryForMap("SELECT reserved_quantity, sold_quantity FROM t_ticket_stock WHERE ticket_tier_id = ?", tier);
        var records = jdbc.queryForMap("SELECT COALESCE(SUM(CASE WHEN status = 'RESERVED' THEN quantity ELSE 0 END), 0) AS reserved_count, COALESCE(SUM(CASE WHEN status = 'SOLD' THEN quantity ELSE 0 END), 0) AS sold_count FROM t_stock_reservation WHERE ticket_tier_id = ?", tier);
        check(number(stock.get("reserved_quantity")) == number(records.get("reserved_count"))
                        && number(stock.get("sold_quantity")) == number(records.get("sold_count")),
                "reservation records reconcile with stock counters");
    }

    long reservationCount(long tier) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id = ?", Long.class, tier);
    }

    void noReservation(String order) {
        check(jdbc.queryForObject("SELECT COUNT(*) FROM t_stock_reservation WHERE order_id = ?", Long.class, order) == 0,
                "failed or rolled-back reservation leaves no row");
    }

    void status(String id, String expected) {
        check(expected.equals(jdbc.queryForObject("SELECT status FROM t_stock_reservation WHERE reservation_id = ?", String.class, id)),
                "rolled-back terminal command retains " + expected);
    }

    static long number(Object value) { return ((Number) value).longValue(); }

    void conflict(Runnable action, String description) {
        expect(InventoryConflictException.class, action, description);
    }

    void expect(Class<? extends RuntimeException> expected, Runnable action, String description) {
        try { action.run(); }
        catch (RuntimeException exception) {
            if (!expected.isInstance(exception)) throw exception;
            check(true, description);
            return;
        }
        throw new AssertionError(description + ": expected " + expected.getSimpleName());
    }

    void http(String method, String path, String body, int status, String code) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("X-Trace-Id", TRACE);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        check(response.statusCode() == status, method + " " + path.substring(0, Math.min(path.length(), 45)) + " => " + status);
        check(response.body().contains("\"traceId\":\"" + TRACE + "\"")
                        && TRACE.equals(response.headers().firstValue("X-Trace-Id").orElse(null)),
                "HTTP result and response header retain trace ID");
        check(code == null ? response.body().contains("\"code\":") : response.body().contains("\"code\":\"" + code + "\""),
                "HTTP response follows Result contract");
    }

    static String json(ReserveStockDTO request) {
        return "{\"orderId\":\"" + request.orderId() + "\",\"sessionId\":" + request.sessionId()
                + ",\"ticketTierId\":" + request.ticketTierId() + ",\"quantity\":" + request.quantity()
                + ",\"expiresAt\":\"" + request.expiresAt() + "\"}";
    }

    void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        passed++;
    }

    void cleanup() throws Exception {
        workers.shutdownNow();
        if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Verification workers are still active; fixtures retained to avoid deleting active rows");
        }
        for (long tier : fixtureTierIds) {
            // First remove this run's order keys; no table-wide deletion or event database access.
            String prefix = "v_" + runId + "_";
            jdbc.update("DELETE FROM t_stock_reservation WHERE ticket_tier_id = ? AND LEFT(order_id, ?) = ?", tier, prefix.length(), prefix);
            jdbc.update("DELETE FROM t_ticket_stock WHERE ticket_tier_id = ?", tier);
        }
        System.out.println("Removed " + fixtureTierIds.size() + " temporary stock fixtures");
    }

    @Configuration(proxyBeanMethods = false)
    public static class VerificationClockConfiguration {
        @Bean
        @Primary
        public Clock verificationClock() { return TEST_CLOCK; }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.now());
        @Override public ZoneId getZone() { return ZoneOffset.ofHours(8); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
        void set(Instant instant) { current.set(instant); }
    }
}
