import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/**
 * 真实 MySQL、独立订单/库存进程验证。活动 HTTP 桩和库存响应故障代理用于可重复注入故障。
 * 只创建随机命名的测试库；结束后停止自身进程并删除自身创建的两个库。
 */
public class OrderWorkflowVerification {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Path root;
    private final Path runPath;
    private final String suffix = UUID.randomUUID().toString().replace("-", "");
    private final String orderSchema = "ticket_order_verify_" + suffix;
    private final String stockSchema = "ticket_stock_verify_" + suffix;
    private final List<Process> children = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean loseReserve = new AtomicBoolean();
    private final AtomicBoolean loseRelease = new AtomicBoolean();
    private final AtomicBoolean inventoryUnavailable = new AtomicBoolean();
    private final AtomicInteger price = new AtomicInteger(199);
    private HttpServer event;
    private HttpServer proxy;
    private Connection orderDb;
    private Connection stockDb;
    private Connection admin;
    private Process order;
    private int orderPort;
    private int stockPort;
    private int serial;
    private int passed;

    private OrderWorkflowVerification(Path root) throws Exception {
        this.root = root;
        this.runPath = root.resolve(".local/order-workflow-verification").resolve(suffix);
        Files.createDirectories(runPath);
    }

    public static void main(String[] args) throws Exception {
        var verification = new OrderWorkflowVerification(Path.of(args[0]).toAbsolutePath());
        try { verification.run(); }
        finally { verification.cleanup(); }
        System.out.println("PASS order workflow: " + verification.passed
                + " checks; isolated test databases and child processes removed.");
    }

    private void run() throws Exception {
        String username = requireEnv("LOCAL_MYSQL_USERNAME");
        String password = requireEnv("LOCAL_MYSQL_PASSWORD");
        admin = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?connectionTimeZone=%2B08:00", username, password);
        // 随机测试库名称由本程序生成，绝不拼接用户提供的库名。
        try (Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + orderSchema);
            sql.execute("CREATE DATABASE " + stockSchema);
        }
        orderDb = DriverManager.getConnection(dbUrl(orderSchema), username, password);
        stockDb = DriverManager.getConnection(dbUrl(stockSchema), username, password);
        event = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        event.setExecutor(executor);
        event.createContext("/", exchange -> {
            Matcher matcher = Pattern.compile("/internal/ticket-tiers/(\\d+)/purchase-rule").matcher(exchange.getRequestURI().getPath());
            if (!matcher.matches()) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            long tier = Long.parseLong(matcher.group(1));
            String rule = "{\"code\":\"OK\",\"message\":\"success\",\"traceId\":\"" + "a".repeat(32)
                    + "\",\"data\":{\"eventId\":1,\"sessionId\":1,\"ticketTierId\":" + tier
                    + ",\"ticketTierName\":\"demo\",\"price\":" + price.get()
                    + ",\"saleStartTime\":\"2020-01-01T00:00:00\",\"saleEndTime\":\"2099-01-01T00:00:00\",\"purchaseLimit\":100}}";
            byte[] body = rule.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        event.start();
        stockPort = freePort();
        Process stock = start("ticket-inventory-service", stockPort, List.of(), stockSchema);
        waitPing(stock, stockPort, "/internal/stocks/101");
        try (Statement sql = stockDb.createStatement()) {
            for (int tier = 101; tier <= 108; tier++) {
                int count = tier == 105 ? 0 : (tier == 107 ? 5 : 50);
                sql.execute("INSERT INTO t_ticket_stock(ticket_tier_id,session_id,total_quantity,available_quantity)"
                        + " VALUES(" + tier + ",1," + count + "," + count + ")");
            }
        }
        proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setExecutor(executor);
        proxy.createContext("/", exchange -> {
            try {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().toString();
                if (inventoryUnavailable.get()) {
                    byte[] error = "{\"code\":\"UPSTREAM_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(503, error.length);
                    exchange.getResponseBody().write(error);
                    return;
                }
                var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + stockPort + path))
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
                String trace = exchange.getRequestHeaders().getFirst("X-Trace-Id");
                if (trace != null) { builder.header("X-Trace-Id", trace); }
                builder.method(exchange.getRequestMethod(), requestBody.length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(requestBody));
                var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                boolean delay = (path.equals("/internal/stock-reservations") && loseReserve.compareAndSet(true, false))
                        || (path.endsWith("/release") && loseRelease.compareAndSet(true, false));
                // 库存已提交，故意延迟响应超过订单读取超时。
                if (delay) { Thread.sleep(1300); }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (trace != null) { exchange.getResponseHeaders().set("X-Trace-Id", trace); }
                exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                exchange.getResponseBody().write(response.body());
            } catch (Exception ignored) {
                // 客户端超时关闭连接属于预期故障注入。
            } finally { exchange.close(); }
        });
        proxy.start();
        restartOrder(false, false, "60s");
        check(post(orderPort, null, 101, 1, "identity").statusCode() == 401, "identity disabled");
        restartOrder(true, false, "60s");
        var normal = post(orderPort, 1L, 101, 2, "normal");
        check(normal.statusCode() == 200 && status(normal).equals("PENDING_PAYMENT"), "normal create");
        String normalNo = field(normal.body(), "orderNo");
        check(normal.body().contains("\"totalAmount\":398"), "server price");
        check(number(stockDb, "SELECT reserved_quantity FROM t_ticket_stock WHERE ticket_tier_id=101") == 2, "reserved");
        check(number(orderDb, "SELECT COUNT(*) FROM t_order_item") == 1, "item saved");
        check(field(post(orderPort, 1L, 101, 2, "normal").body(), "orderNo").equals(normalNo), "same key");
        check(post(orderPort, 1L, 101, 1, "normal").statusCode() == 409, "different parameters");
        check(get(orderPort, 2L, normalNo).statusCode() == 404, "owner isolation");
        check(get(orderPort, 1L, normalNo).statusCode() == 200, "owner query");
        check(post(orderPort, 1L, 101, 0, "bad").statusCode() == 400, "validation");
        price.set(299);
        check(get(orderPort, 1L, normalNo).body().contains("\"unitPrice\":199"), "price snapshot");
        price.set(199);
        var preview = HTTP.send(HttpRequest.newBuilder(URI.create(base(orderPort) + "/api/orders/preview"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"ticketTierId\":101,\"quantity\":2}")).build(),
                HttpResponse.BodyHandlers.ofString());
        check(preview.statusCode() == 200 && preview.body().contains("\"totalAmount\":398"), "preview regression");

        // 两个订单进程共享同一库，验证多实例购买幂等和唯一键竞争。
        int secondPort = freePort();
        Process second = startOrder(secondPort, true, false, "60s");
        waitPing(second, secondPort, "/api/orders/ping");
        List<Future<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int port = i % 2 == 0 ? orderPort : secondPort;
            requests.add(executor.submit(() -> post(port, 1L, 101, 1, "concurrent")));
        }
        Set<String> numbers = new HashSet<>();
        for (var future : requests) {
            var response = future.get(30, TimeUnit.SECONDS);
            check(response.statusCode() == 200 || response.statusCode() == 202, "concurrent response");
            numbers.add(field(response.body(), "orderNo"));
        }
        check(numbers.size() == 1, "one order across instances");
        check(number(orderDb, "SELECT COUNT(*) FROM t_order WHERE idempotency_key='concurrent'") == 1, "unique row");
        check(number(stockDb, "SELECT reserved_quantity FROM t_ticket_stock WHERE ticket_tier_id=101") == 3, "one deduction");
        stop(second);

        var insufficient = post(orderPort, 1L, 105, 1, "insufficient");
        check(status(insufficient).equals("CREATE_FAILED"), "insufficient failure recorded");
        check(number(stockDb, "SELECT COUNT(*) FROM t_stock_reservation WHERE ticket_tier_id=105") == 0, "no phantom reserve");
        check(field(post(orderPort, 1L, 105, 1, "insufficient").body(), "orderNo")
                .equals(field(insufficient.body(), "orderNo")), "failure is idempotent");

        // 注入订单项写入失败，确认主表和订单项事务一起回滚。
        try (Statement sql = orderDb.createStatement()) {
            sql.execute("CREATE TRIGGER verify_item_failure BEFORE INSERT ON t_order_item FOR EACH ROW"
                    + " BEGIN IF NEW.ticket_tier_id=999 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='verify rollback'; END IF; END");
        }
        check(post(orderPort, 1L, 999, 1, "rollback").statusCode() == 500, "item failure");
        check(number(orderDb, "SELECT COUNT(*) FROM t_order WHERE idempotency_key='rollback'") == 0, "main rolled back");
        try (Statement sql = orderDb.createStatement()) { sql.execute("DROP TRIGGER verify_item_failure"); }

        // 库存成功但响应超时，订单返回已落库的处理中结果。
        loseReserve.set(true);
        var lost = post(orderPort, 1L, 102, 2, "lost");
        check(lost.statusCode() == 202 && status(lost).equals("STOCK_PENDING"), "uncertain request accepted");
        String lostNo = field(lost.body(), "orderNo");
        check(number(stockDb, "SELECT reserved_quantity FROM t_ticket_stock WHERE ticket_tier_id=102") == 2, "commit before timeout");
        check(number(orderDb, "SELECT attempt_count FROM t_order WHERE order_no='" + lostNo + "'") == 1, "no automatic HTTP retry");
        due(lostNo);
        restartOrder(true, true, "60s");
        awaitStatus(lostNo, "PENDING_PAYMENT");
        check(number(stockDb, "SELECT reserved_quantity FROM t_ticket_stock WHERE ticket_tier_id=102") == 2, "restart recovers once");

        // 模拟领取者宕机：新进程不能抢未到期租约，到期后继续原订单。
        secondPort = freePort();
        second = startOrder(secondPort, true, true, "60s");
        waitPing(second, secondPort, "/api/orders/ping");
        long attemptsBeforeLease = number(orderDb, "SELECT attempt_count FROM t_order WHERE order_no='" + lostNo + "'");
        update("UPDATE t_order SET status='STOCK_PENDING', next_attempt_at=NOW(3), lease_token='"
                + "b".repeat(32) + "', lease_until=TIMESTAMPADD(SECOND,2,NOW(3)) WHERE order_no='" + lostNo + "'");
        Thread.sleep(400);
        check(status(get(orderPort, 1L, lostNo)).equals("STOCK_PENDING"), "active lease respected");
        awaitStatus(lostNo, "PENDING_PAYMENT");
        check(number(stockDb, "SELECT reserved_quantity FROM t_ticket_stock WHERE ticket_tier_id=102") == 2, "expired lease recovery");
        check(number(orderDb, "SELECT attempt_count FROM t_order WHERE order_no='" + lostNo + "'")
                == attemptsBeforeLease + 1, "one recovery claimant across two instances");
        stop(second);

        // 不同订单竞争 5 张库存，最终只有 5 张预留成功。
        requests.clear();
        for (int i = 0; i < 12; i++) {
            String key = "race_" + i;
            requests.add(executor.submit(() -> post(orderPort, 1L, 107, 1, key)));
        }
        int wins = 0;
        for (var future : requests) {
            var response = future.get(30, TimeUnit.SECONDS);
            if (status(response).equals("PENDING_PAYMENT")) { wins++; }
            check(Set.of("PENDING_PAYMENT", "CREATE_FAILED").contains(status(response)), "race terminal state");
        }
        check(wins == 5, "five winners");
        check(number(stockDb, "SELECT available_quantity FROM t_ticket_stock WHERE ticket_tier_id=107") == 0, "no oversell");

        restartOrder(true, true, "2s");
        var expired = post(orderPort, 1L, 103, 1, "expire");
        check(status(expired).equals("PENDING_PAYMENT"), "short-window created");
        String expiredNo = field(expired.body(), "orderNo");
        awaitStatus(expiredNo, "CLOSED");
        check(number(stockDb, "SELECT available_quantity FROM t_ticket_stock WHERE ticket_tier_id=103") == 50, "expiry release");

        // 释放已经提交、响应丢失：CLOSING 继续幂等释放，不重复加库存。
        loseRelease.set(true);
        var releasing = post(orderPort, 1L, 104, 1, "release_lost");
        String releaseNo = field(releasing.body(), "orderNo");
        awaitStatus(releaseNo, "CLOSING");
        awaitStatus(releaseNo, "CLOSED");
        check(number(stockDb, "SELECT available_quantity FROM t_ticket_stock WHERE ticket_tier_id=104") == 50, "release recovered once");

        // 已到期的未知预留：仍用原到期时间核对，不能生成新时间再次占库存。
        restartOrder(true, false, "2s");
        loseReserve.set(true);
        var expiredLost = post(orderPort, 1L, 106, 1, "expired_lost");
        check(status(expiredLost).equals("STOCK_PENDING"), "expired lost pending");
        String expiredLostNo = field(expiredLost.body(), "orderNo");
        Thread.sleep(2400);
        due(expiredLostNo);
        restartOrder(true, true, "60s");
        awaitStatus(expiredLostNo, "CLOSED");
        check(number(stockDb, "SELECT available_quantity FROM t_ticket_stock WHERE ticket_tier_id=106") == 50, "expired unknown reclaimed");

        // 请求未到达库存且订单已到期：不能补出一条新预留。
        restartOrder(true, false, "2s");
        inventoryUnavailable.set(true);
        var absent = post(orderPort, 1L, 106, 1, "absent_expired");
        check(absent.statusCode() == 202 && status(absent).equals("STOCK_PENDING"), "unavailable stays pending");
        String absentNo = field(absent.body(), "orderNo");
        Thread.sleep(2400);
        inventoryUnavailable.set(false);
        due(absentNo);
        restartOrder(true, true, "60s");
        awaitStatus(absentNo, "CREATE_FAILED");
        check(number(stockDb, "SELECT COUNT(*) FROM t_stock_reservation WHERE order_id='" + absentNo + "'") == 0,
                "no late new reservation");

        // 库存已 SOLD 不能等同已支付，也不能当作释放成功。
        var sold = post(orderPort, 1L, 108, 1, "sold");
        String soldNo = field(sold.body(), "orderNo");
        String reservation = field(sold.body(), "reservationId");
        var confirm = HTTP.send(HttpRequest.newBuilder(URI.create(base(stockPort)
                + "/internal/stock-reservations/" + reservation + "/confirm"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        check(confirm.statusCode() == 200, "manual stock confirm");
        update("UPDATE t_order SET expires_at=TIMESTAMPADD(SECOND,-1,NOW(3)), next_attempt_at=NOW(3) WHERE order_no='" + soldNo + "'");
        awaitStatus(soldNo, "REVIEW_REQUIRED");
        check(number(stockDb, "SELECT sold_quantity FROM t_ticket_stock WHERE ticket_tier_id=108") == 1, "sold not released");
        check(number(orderDb, "SELECT COUNT(*) FROM t_order WHERE status IN ('STOCK_PENDING','CLOSING')") == 0, "no stuck test orders");
    }

    private Process start(String module, int port, List<String> extra, String schema) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-jar",
                root.resolve(module + "/target/" + module + "-1.0-SNAPSHOT.jar").toString(),
                "--server.port=" + port, "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                "--spring.datasource.url=" + dbUrl(schema)));
        arguments.addAll(extra);
        Process process = new ProcessBuilder(arguments).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(runPath.resolve(module + "_" + serial++ + ".log").toFile()).start();
        children.add(process);
        return process;
    }

    private Process startOrder(int port, boolean dev, boolean recovery, String window) throws Exception {
        return start("ticket-order-service", port, List.of(
                "--ticket.order.dev-identity-enabled=" + dev, "--ticket.order.recovery-enabled=" + recovery,
                "--ticket.order.payment-window=" + window, "--ticket.order.recovery-delay-ms=200",
                "--ticket.order.retry-base=1500ms", "--ticket.order.retry-max=2s",
                "--ticket.clients.inventory.connect-timeout=200ms", "--ticket.clients.inventory.read-timeout=500ms",
                "--spring.cloud.discovery.client.simple.instances.ticket-inventory-service[0].uri="
                        + base(proxy.getAddress().getPort()),
                "--spring.cloud.discovery.client.simple.instances.ticket-event-service[0].uri="
                        + base(event.getAddress().getPort())), orderSchema);
    }

    private void restartOrder(boolean dev, boolean recovery, String window) throws Exception {
        if (order != null) { stop(order); }
        orderPort = freePort();
        order = startOrder(orderPort, dev, recovery, window);
        waitPing(order, orderPort, "/api/orders/ping");
    }

    private void waitPing(Process process, int port, String path) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < end) {
            if (!process.isAlive()) { throw new AssertionError("Process exited; logs: " + runPath); }
            try {
                var response = HTTP.send(HttpRequest.newBuilder(URI.create(base(port) + path))
                        .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 503) { return; }
            } catch (Exception ignored) {}
            Thread.sleep(100);
        }
        throw new AssertionError("Startup timed out; logs: " + runPath);
    }

    private HttpResponse<String> post(int port, Long user, long tier, int quantity, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base(port) + "/api/orders"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (user != null) { builder.header("X-Dev-User-Id", user.toString()); }
        return HTTP.send(builder.POST(HttpRequest.BodyPublishers.ofString("{\"ticketTierId\":" + tier
                + ",\"quantity\":" + quantity + ",\"idempotencyKey\":\"" + key + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, Long user, String no) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base(port) + "/api/orders/" + no))
                .timeout(Duration.ofSeconds(3)).header("X-Dev-User-Id", user.toString()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void awaitStatus(String no, String expected) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String last = "";
        while (System.nanoTime() < end) {
            last = status(get(orderPort, 1L, no));
            if (last.equals(expected)) { passed++; return; }
            if (last.equals("REVIEW_REQUIRED") && !expected.equals(last)) { break; }
            Thread.sleep(100);
        }
        throw new AssertionError("Expected " + expected + ", actual " + last + ", order=" + no);
    }

    private void due(String no) throws Exception {
        update("UPDATE t_order SET next_attempt_at=NOW(3), lease_until=NULL, lease_token=NULL WHERE order_no='" + no + "'");
    }

    private void update(String sql) throws Exception { try (Statement statement = orderDb.createStatement()) { statement.executeUpdate(sql); } }
    private static long number(Connection db, String sql) throws Exception {
        try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next(); return result.getLong(1);
        }
    }
    private void check(boolean condition, String name) {
        if (!condition) { throw new AssertionError(name + "; logs: " + runPath); }
        passed++;
    }
    private static String status(HttpResponse<String> response) { return field(response.body(), "status"); }
    private static String field(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) { throw new AssertionError("Missing " + field + ": " + body); }
        return matcher.group(1);
    }
    private static int freePort() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    private static String base(int port) { return "http://127.0.0.1:" + port; }
    private static String dbUrl(String name) { return "jdbc:mysql://127.0.0.1:3306/" + name
            + "?characterEncoding=UTF-8&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true"; }
    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) { throw new IllegalStateException("Set " + name); }
        return value;
    }
    private static void stop(Process process) throws Exception {
        if (process.isAlive()) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); } }
    }
    private void cleanup() throws Exception {
        for (Process process : children) { stop(process); }
        if (event != null) { event.stop(0); }
        if (proxy != null) { proxy.stop(0); }
        executor.shutdownNow();
        if (orderDb != null) { orderDb.close(); }
        if (stockDb != null) { stockDb.close(); }
        if (admin != null) {
            try (Statement sql = admin.createStatement()) {
                if (!orderSchema.matches("ticket_order_verify_[0-9a-f]{32}")
                        || !stockSchema.matches("ticket_stock_verify_[0-9a-f]{32}")) { throw new AssertionError("Unsafe fixture name"); }
                sql.execute("DROP DATABASE IF EXISTS " + orderSchema);
                sql.execute("DROP DATABASE IF EXISTS " + stockSchema);
            } finally { admin.close(); }
        }
    }
}
