import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real Order -> Inventory -> MySQL verification with Nacos discovery, using JDK APIs and JDBC. */
public class OrderInventoryChainVerification {
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String DEFAULT_DB_URL = "jdbc:mysql://127.0.0.1:3306/ticket_inventory"
            + "?characterEncoding=UTF-8&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Connection database;
    private final String orderBase;
    private final String inventoryBase;
    private final String orderPrefix = "VERIFYOI" + UUID.randomUUID().toString().replace("-", "");
    private final LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.ofHours(8))
            .plusMinutes(15).truncatedTo(ChronoUnit.MILLIS);
    private long tierId;
    private long sessionId;
    private long missingTierId;
    private boolean fixtureOwned;
    private int passed;

    private OrderInventoryChainVerification(Connection database, String orderBase, String inventoryBase) {
        this.database = database;
        this.orderBase = orderBase;
        this.inventoryBase = inventoryBase;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Provide the Order and Inventory HTTP base URLs.");
        }
        String username = requireEnvironment("LOCAL_MYSQL_USERNAME");
        String password = requireEnvironment("LOCAL_MYSQL_PASSWORD");
        String configuredUrl = System.getenv("INVENTORY_DB_URL");
        String dbUrl = configuredUrl == null || configuredUrl.isBlank() ? DEFAULT_DB_URL : configuredUrl;
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection connection = DriverManager.getConnection(dbUrl, username, password)) {
            var verification = new OrderInventoryChainVerification(connection, args[0], args[1]);
            Throwable failure = null;
            try {
                verification.run();
            } catch (Exception | AssertionError exception) {
                failure = exception;
                throw exception;
            } finally {
                try {
                    verification.cleanup();
                } catch (Exception cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
            System.out.println("PASS real Order -> Inventory -> MySQL chain with Nacos discovery: "
                    + verification.passed + " checks; generated fixture rows removed.");
        }
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Set " + name + " in the process environment.");
        }
        return value;
    }

    private void run() throws Exception {
        createFixture();
        String firstOrder = orderPrefix + "A";
        String secondOrder = orderPrefix + "B";
        String firstPayload = payload(firstOrder, tierId, 2);

        var first = post(orderBase + "/internal/orders/stock-reservations", firstPayload);
        String firstId = requireReservation(first, firstOrder, 2, "RESERVED");
        assertStock(8, 2, 0);
        assertReservationCount(1);

        var repeated = post(orderBase + "/internal/orders/stock-reservations", firstPayload);
        check(firstId.equals(requireReservation(repeated, firstOrder, 2, "RESERVED")),
                "Same request must return the original reservation ID");
        assertStock(8, 2, 0);
        assertReservationCount(1);

        requireError(post(orderBase + "/internal/orders/stock-reservations", payload(firstOrder, tierId, 3)),
                409, "CONFLICT");
        requireError(post(orderBase + "/internal/orders/stock-reservations", payload(orderPrefix + "SHORT", tierId, 9)),
                409, "CONFLICT");
        requireError(post(orderBase + "/internal/orders/stock-reservations", payload(orderPrefix + "MISSING", missingTierId, 1)),
                404, "RESOURCE_NOT_FOUND");
        requireError(post(orderBase + "/internal/orders/stock-reservations", "{broken-json"),
                400, "BAD_REQUEST");
        requireError(post(orderBase + "/internal/orders/stock-reservations", payload(orderPrefix + "ZERO", tierId, 0)),
                400, "BAD_REQUEST");
        assertStock(8, 2, 0);
        assertReservationCount(1);

        var released = post(inventoryBase + "/internal/stock-reservations/" + firstId + "/release", null);
        check(firstId.equals(requireReservation(released, firstOrder, 2, "RELEASED")), "Release keeps reservation ID");
        var releasedRetry = post(orderBase + "/internal/orders/stock-reservations", firstPayload);
        check(firstId.equals(requireReservation(releasedRetry, firstOrder, 2, "RELEASED")),
                "Order retry returns RELEASED without re-reserving");
        assertStock(10, 0, 0);
        assertReservationCount(1);

        String secondPayload = payload(secondOrder, tierId, 3);
        String secondId = requireReservation(post(orderBase + "/internal/orders/stock-reservations", secondPayload),
                secondOrder, 3, "RESERVED");
        assertStock(7, 3, 0);
        var sold = post(inventoryBase + "/internal/stock-reservations/" + secondId + "/confirm", null);
        check(secondId.equals(requireReservation(sold, secondOrder, 3, "SOLD")), "Confirm keeps reservation ID");
        var soldRetry = post(orderBase + "/internal/orders/stock-reservations", secondPayload);
        check(secondId.equals(requireReservation(soldRetry, secondOrder, 3, "SOLD")),
                "Order retry returns SOLD without re-reserving");
        requireError(post(inventoryBase + "/internal/stock-reservations/" + secondId + "/release", null),
                409, "CONFLICT");
        assertStock(7, 0, 3);
        assertReservationCount(2);
        assertFinalBalance();
    }

    private void createFixture() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            long candidate = 9_000_000_000_000L
                    + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000_000L);
            if (scalar("SELECT COUNT(*) FROM t_ticket_stock WHERE ticket_tier_id IN (?, ?)", candidate, candidate + 2) == 0
                    && scalar("SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id IN (?, ?)", candidate, candidate + 2) == 0) {
                tierId = candidate;
                sessionId = candidate + 1;
                missingTierId = candidate + 2;
                try (PreparedStatement insert = database.prepareStatement("INSERT INTO t_ticket_stock"
                        + " (ticket_tier_id, session_id, total_quantity, available_quantity, reserved_quantity, sold_quantity)"
                        + " VALUES (?, ?, 10, 10, 0, 0)")) {
                    insert.setLong(1, tierId);
                    insert.setLong(2, sessionId);
                    if (insert.executeUpdate() != 1) {
                        throw new IllegalStateException("Fixture insert failed");
                    }
                }
                fixtureOwned = true;
                return;
            }
        }
        throw new IllegalStateException("Could not allocate unused temporary fixture IDs");
    }

    private String payload(String orderId, long requestedTierId, int quantity) {
        String expiry = expiresAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"));
        return "{\"orderId\":\"" + orderId + "\",\"sessionId\":" + sessionId
                + ",\"ticketTierId\":" + requestedTierId + ",\"quantity\":" + quantity
                + ",\"expiresAt\":\"" + expiry + "\"}";
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("X-Trace-Id", TRACE)
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String requireReservation(HttpResponse<String> response, String orderId, int quantity, String status) {
        check(response.statusCode() == 200, "Expected success, received " + response.statusCode() + ": " + response.body());
        check("OK".equals(stringField(response.body(), "code")), "Successful Result code");
        requireTrace(response);
        String reservationId = stringField(response.body(), "reservationId");
        check(reservationId.matches("[0-9a-f]{32}"), "Reservation ID format");
        check(orderId.equals(stringField(response.body(), "orderId")), "Matching order ID");
        check(numberField(response.body(), "sessionId") == sessionId, "Matching session ID");
        check(numberField(response.body(), "ticketTierId") == tierId, "Matching tier ID");
        check(numberField(response.body(), "quantity") == quantity, "Matching quantity");
        check(expiresAt.equals(LocalDateTime.parse(stringField(response.body(), "expiresAt"))), "Matching expiry");
        check(status.equals(stringField(response.body(), "status")), "Expected reservation status " + status);
        return reservationId;
    }

    private void requireError(HttpResponse<String> response, int status, String code) {
        check(response.statusCode() == status, "Expected HTTP " + status + ", received "
                + response.statusCode() + ": " + response.body());
        check(code.equals(stringField(response.body(), "code")), "Expected error code " + code);
        requireTrace(response);
    }

    private void requireTrace(HttpResponse<String> response) {
        check(TRACE.equals(response.headers().firstValue("X-Trace-Id").orElse(null)), "HTTP trace header preserved");
        check(TRACE.equals(stringField(response.body(), "traceId")), "Result trace ID preserved");
    }

    // Only controlled contract fields containing ASCII IDs, ISO timestamps and enum codes are read.
    private String stringField(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Missing string contract field: " + name);
        }
        return matcher.group(1);
    }

    private long numberField(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*([0-9]+)").matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("Missing numeric contract field: " + name);
        }
        return Long.parseLong(matcher.group(1));
    }

    private void assertStock(int available, int reserved, int sold) throws Exception {
        try (PreparedStatement query = database.prepareStatement("SELECT total_quantity, available_quantity,"
                + " reserved_quantity, sold_quantity FROM t_ticket_stock WHERE ticket_tier_id = ?")) {
            query.setLong(1, tierId);
            try (ResultSet rows = query.executeQuery()) {
                check(rows.next(), "Fixture stock exists");
                check(rows.getInt(1) == 10 && rows.getInt(2) == available
                        && rows.getInt(3) == reserved && rows.getInt(4) == sold, "Expected database stock quantities");
                check(!rows.next(), "One stock row per tier");
            }
        }
    }

    private void assertReservationCount(int expected) throws Exception {
        check(scalar("SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id = ?", tierId) == expected,
                "Expected reservation row count " + expected);
    }

    private void assertFinalBalance() throws Exception {
        check(scalar("SELECT COALESCE(SUM(quantity), 0) FROM t_stock_reservation WHERE ticket_tier_id = ? AND status = 'RESERVED'",
                tierId) == 0, "No reserved quantities remain");
        check(scalar("SELECT COALESCE(SUM(quantity), 0) FROM t_stock_reservation WHERE ticket_tier_id = ? AND status = 'SOLD'",
                tierId) == 3, "Sold records match sold stock");
        check(scalar("SELECT COALESCE(SUM(quantity), 0) FROM t_stock_reservation WHERE ticket_tier_id = ? AND status = 'RELEASED'",
                tierId) == 2, "Released record retained for idempotence");
    }

    private long scalar(String sql, long... arguments) throws Exception {
        try (PreparedStatement query = database.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                query.setLong(i + 1, arguments[i]);
            }
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Scalar query returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    private void cleanup() throws Exception {
        if (!fixtureOwned) {
            return;
        }
        try (PreparedStatement delete = database.prepareStatement("DELETE FROM t_stock_reservation"
                + " WHERE ticket_tier_id = ? AND order_id LIKE ?")) {
            delete.setLong(1, tierId);
            delete.setString(2, orderPrefix + "%");
            delete.executeUpdate();
        }
        if (scalar("SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id = ?", tierId) != 0) {
            throw new IllegalStateException("Unowned reservation references fixture; retaining stock for review");
        }
        try (PreparedStatement delete = database.prepareStatement("DELETE FROM t_ticket_stock"
                + " WHERE ticket_tier_id = ? AND session_id = ?")) {
            delete.setLong(1, tierId);
            delete.setLong(2, sessionId);
            check(delete.executeUpdate() == 1, "Temporary fixture stock removed");
        }
        check(scalar("SELECT COUNT(*) FROM t_stock_reservation WHERE order_id LIKE '" + orderPrefix + "%' ") == 0,
                "No generated reservations remain");
        fixtureOwned = false;
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        passed++;
    }
}
