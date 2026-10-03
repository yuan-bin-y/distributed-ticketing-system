import com.byy.ticket.order.client.EventClient;
import com.byy.ticket.order.config.RestClientConfig;
import com.byy.ticket.order.config.OrderClockConfig;
import com.byy.ticket.order.config.OrderWorkflowProperties;
import com.byy.ticket.order.controller.OrderController;
import com.byy.ticket.order.controller.InternalOrderStockController;
import com.byy.ticket.order.service.impl.OrderServiceImpl;
import com.byy.ticket.order.web.OrderIdentityResolver;
import com.byy.ticket.order.web.filter.TraceIdFilter;
import com.byy.ticket.order.web.handler.GlobalExceptionHandler;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import java.time.Clock;
import com.byy.ticket.order.client.InventoryClient;
import com.byy.ticket.order.client.dto.StockReservationRequest;
import com.byy.ticket.order.client.dto.StockReservationResponse;
import com.byy.ticket.order.client.exception.InventoryServiceCallException;
import com.byy.ticket.order.config.InventoryClientProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real HTTP and Spring LoadBalancer verification; stubs replace Nacos and MySQL. */
public class InventoryClientVerification {
    /** 此验证只覆盖 HTTP 客户端、内部预留和预览；正式订单数据库由 workflow 验证覆盖。 */
    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"})
    @Import({RestClientConfig.class, OrderClockConfig.class, EventClient.class, InventoryClient.class,
            InternalOrderStockController.class, OrderController.class, OrderIdentityResolver.class,
            TraceIdFilter.class, GlobalExceptionHandler.class})
    public static class ClientOnlyApplication {
        @Bean
        OrderWorkflowProperties properties() {
            return new OrderWorkflowProperties(false, null, false, null, null, null, null);
        }

        /** 不构建数据库和恢复组件；本测试不调用 create/getOrder。 */
        @Bean
        OrderServiceImpl orderService(EventClient event, InventoryClient inventory, Clock clock,
                                      OrderWorkflowProperties properties) {
            return new OrderServiceImpl(event, inventory, clock, null, null, null, null, properties);
        }
    }
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String RESERVATION_ID = "11111111111111111111111111111111";
    private static final LocalDateTime EXPIRY = LocalDateTime.parse("2030-01-01T12:15:00.123");
    private static final String RESERVE_JSON = "{\"orderId\":\"ORDER_VERIFY\",\"sessionId\":2,"
            + "\"ticketTierId\":31,\"quantity\":2,\"expiresAt\":\"" + EXPIRY + "\"}";
    private static final AtomicReference<String> MODE = new AtomicReference<>("OK");
    private static final AtomicInteger INVENTORY_CALLS = new AtomicInteger();
    private static final AtomicInteger EVENT_CALLS = new AtomicInteger();
    private static final ConcurrentLinkedQueue<Received> RECEIVED = new ConcurrentLinkedQueue<>();
    private static final JsonMapper JSON = new JsonMapper();
    private static int checks;

    private record Received(String instance, String method, String path, String trace, String body) { }

    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        HttpServer a = inventoryStub("instance-a", executor);
        HttpServer b = inventoryStub("instance-b", executor);
        HttpServer event = eventStub(executor);
        try (var context = SpringApplication.run(ClientOnlyApplication.class,
                "--server.port=0", "--spring.cloud.nacos.discovery.enabled=false",
                "--logging.level.root=ERROR", "--spring.main.banner-mode=off",
                "--ticket.clients.inventory.connect-timeout=1s",
                "--ticket.clients.inventory.read-timeout=200ms",
                "--spring.cloud.discovery.client.simple.instances.ticket-inventory-service[0].uri=http://127.0.0.1:"
                        + a.getAddress().getPort(),
                "--spring.cloud.discovery.client.simple.instances.ticket-inventory-service[1].uri=http://127.0.0.1:"
                        + b.getAddress().getPort(),
                "--spring.cloud.discovery.client.simple.instances.ticket-event-service[0].uri=http://127.0.0.1:"
                        + event.getAddress().getPort())) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            InventoryClient client = context.getBean(InventoryClient.class);
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            verifyReserveHttp(http, port);
            verifyOperations(client);
            verifyInputValidation(client, http, port);
            verifyResponseValidation(client);
            verifyErrors(http, port);
            verifyPreviewRegression(http, port);

            InventoryClient missing = new InventoryClient(
                    context.getBean("inventoryRestClientBuilder", RestClient.Builder.class),
                    new InventoryClientProperties("missing-inventory-verification",
                            Duration.ofSeconds(1), Duration.ofMillis(200)));
            expectReason(() -> missing.reserve(request()), InventoryServiceCallException.Reason.UNAVAILABLE,
                    "missing discovery instance");
            a.stop(0);
            b.stop(0);
            MODE.set("OK");
            HttpResponse<String> refused = orderReserve(http, port, RESERVE_JSON);
            check(refused.statusCode() == 503, "connection refusal returns 503");
            check("UPSTREAM_UNAVAILABLE".equals(json(refused.body()).get("code")), "connection refusal code");
            System.out.println("PASS missing instances and connection refusal");
            System.out.println("Inventory client verification passed: " + checks + " checks.");
        } finally {
            a.stop(0);
            b.stop(0);
            event.stop(0);
            executor.shutdownNow();
        }
    }

    private static void verifyReserveHttp(HttpClient http, int port) throws Exception {
        Set<String> instances = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            RECEIVED.clear();
            int before = INVENTORY_CALLS.get();
            HttpResponse<String> response = orderReserve(http, port, RESERVE_JSON);
            check(response.statusCode() == 200, "reserve HTTP success");
            check(INVENTORY_CALLS.get() == before + 1, "one HTTP call per reserve");
            check(EVENT_CALLS.get() == 0, "internal reserve makes no Event call");
            Received received = RECEIVED.remove();
            instances.add(received.instance());
            check("POST".equals(received.method()), "reserve uses POST");
            check("/internal/stock-reservations".equals(received.path()), "reserve path");
            check(TRACE.equals(received.trace()), "reserve propagates trace header");
            Map<String, Object> input = json(received.body());
            check("ORDER_VERIFY".equals(input.get("orderId")), "serialized orderId");
            check(number(input, "sessionId") == 2 && number(input, "ticketTierId") == 31
                    && number(input, "quantity") == 2, "serialized IDs and quantity");
            check(EXPIRY.equals(LocalDateTime.parse((String) input.get("expiresAt"))), "serialized expiry precision");
            Map<String, Object> result = json(response.body());
            check("OK".equals(result.get("code")) && TRACE.equals(result.get("traceId")), "uniform success wrapper");
            check(TRACE.equals(response.headers().firstValue("X-Trace-Id").orElse(null)), "response trace header");
            check("RESERVED".equals(data(result).get("status")), "reserve data returned through Order");
        }
        check(instances.size() == 2, "LoadBalancer uses both discovered inventory instances");
        System.out.println("PASS Order -> Inventory HTTP body, trace propagation, two-instance load balancing");
    }

    private static void verifyOperations(InventoryClient client) {
        MODE.set("OK");
        RECEIVED.clear();
        check("SOLD".equals(client.confirm(RESERVATION_ID).status()), "confirm returns SOLD");
        verifyReceived("POST", "/internal/stock-reservations/" + RESERVATION_ID + "/confirm");
        check("RELEASED".equals(client.release(RESERVATION_ID).status()), "release returns RELEASED");
        verifyReceived("POST", "/internal/stock-reservations/" + RESERVATION_ID + "/release");
        check(RESERVATION_ID.equals(client.getReservation(RESERVATION_ID).reservationId()), "get ID matches");
        verifyReceived("GET", "/internal/stock-reservations/" + RESERVATION_ID);
        for (String state : new String[]{"RESERVED", "SOLD", "RELEASED"}) {
            MODE.set(state);
            check(state.equals(client.reserve(request()).status()), "reserve replay accepts " + state);
            check(state.equals(client.getReservation(RESERVATION_ID).status()), "get accepts " + state);
        }
        MODE.set("RESERVED");
        expectReason(() -> client.confirm(RESERVATION_ID), InventoryServiceCallException.Reason.INVALID_RESPONSE,
                "confirm rejects RESERVED result");
        expectReason(() -> client.release(RESERVATION_ID), InventoryServiceCallException.Reason.INVALID_RESPONSE,
                "release rejects RESERVED result");
        MODE.set("RELEASED");
        expectReason(() -> client.confirm(RESERVATION_ID), InventoryServiceCallException.Reason.INVALID_RESPONSE,
                "confirm rejects RELEASED result");
        MODE.set("SOLD");
        expectReason(() -> client.release(RESERVATION_ID), InventoryServiceCallException.Reason.INVALID_RESPONSE,
                "release rejects SOLD result");
        MODE.set("OK");
        System.out.println("PASS reserve/get/confirm/release paths, trace headers, terminal result checks");
    }

    private static void verifyInputValidation(InventoryClient client, HttpClient http, int port) throws Exception {
        int before = INVENTORY_CALLS.get();
        StockReservationRequest[] badInputs = {
                null,
                new StockReservationRequest("bad space", 2L, 31L, 2, EXPIRY),
                new StockReservationRequest("ORDER_VERIFY", 0L, 31L, 2, EXPIRY),
                new StockReservationRequest("ORDER_VERIFY", 2L, 0L, 2, EXPIRY),
                new StockReservationRequest("ORDER_VERIFY", 2L, 31L, 0, EXPIRY),
                new StockReservationRequest("ORDER_VERIFY", 2L, 31L, 2, null),
                new StockReservationRequest("ORDER_VERIFY", 2L, 31L, 2, EXPIRY.plusNanos(1))
        };
        for (StockReservationRequest bad : badInputs) {
            expectIllegal(() -> client.reserve(bad), "invalid reserve input rejected locally");
        }
        expectIllegal(() -> client.getReservation("invalid"), "invalid get ID rejected locally");
        expectIllegal(() -> client.confirm(null), "invalid confirm ID rejected locally");
        expectIllegal(() -> client.release(""), "invalid release ID rejected locally");
        for (String body : new String[]{"{}", "not-json", RESERVE_JSON.replace("\"quantity\":2", "\"quantity\":0")}) {
            check(orderReserve(http, port, body).statusCode() == 400, "internal Order request validation");
        }
        check(INVENTORY_CALLS.get() == before, "invalid inputs never contact inventory");
        System.out.println("PASS input validation without remote requests");
    }

    private static void verifyResponseValidation(InventoryClient client) {
        for (String mode : new String[]{"BAD_JSON", "BAD_CODE", "EMPTY_BODY", "EMPTY_DATA", "WRONG_ID", "WRONG_ORDER",
                "WRONG_SESSION", "WRONG_TIER", "WRONG_QUANTITY", "WRONG_EXPIRY", "BAD_STATUS"}) {
            MODE.set(mode);
            expectReason(() -> client.reserve(request()), InventoryServiceCallException.Reason.INVALID_RESPONSE, mode);
        }
        MODE.set("MISMATCH_ID");
        expectReason(() -> client.getReservation(RESERVATION_ID), InventoryServiceCallException.Reason.INVALID_RESPONSE,
                "get refuses response for another reservation");
        expectReason(() -> client.confirm(RESERVATION_ID), InventoryServiceCallException.Reason.INVALID_RESPONSE,
                "confirm refuses response for another reservation");
        expectReason(() -> client.release(RESERVATION_ID), InventoryServiceCallException.Reason.INVALID_RESPONSE,
                "release refuses response for another reservation");
        MODE.set("OK");
        System.out.println("PASS malformed and mismatched response rejection");
    }

    private static void verifyErrors(HttpClient http, int port) throws Exception {
        String[] modes = {"BAD_REQUEST", "NOT_FOUND", "CONFLICT", "WRONG_400_CODE", "WRONG_404_CODE", "WRONG_409_CODE",
                "SERVER_ERROR", "REDIRECT", "UNKNOWN_4XX", "BAD_JSON", "DELAY", "BODY_DELAY"};
        int[] statuses = {400, 404, 409, 502, 502, 502, 503, 502, 502, 502, 504, 504};
        String[] codes = {"BAD_REQUEST", "RESOURCE_NOT_FOUND", "CONFLICT",
                "INVALID_UPSTREAM_RESPONSE", "INVALID_UPSTREAM_RESPONSE", "INVALID_UPSTREAM_RESPONSE", "UPSTREAM_UNAVAILABLE",
                "INVALID_UPSTREAM_RESPONSE", "INVALID_UPSTREAM_RESPONSE", "INVALID_UPSTREAM_RESPONSE",
                "UPSTREAM_TIMEOUT", "UPSTREAM_TIMEOUT"};
        for (int i = 0; i < modes.length; i++) {
            MODE.set(modes[i]);
            int before = INVENTORY_CALLS.get();
            HttpResponse<String> response = orderReserve(http, port, RESERVE_JSON);
            check(response.statusCode() == statuses[i], modes[i] + " HTTP status: expected " + statuses[i]
                    + ", received " + response.statusCode() + ", response " + response.body());
            Map<String, Object> body = json(response.body());
            check(codes[i].equals(body.get("code")), modes[i] + " business code");
            check(TRACE.equals(body.get("traceId")), modes[i] + " retains trace");
            check(INVENTORY_CALLS.get() == before + 1, modes[i] + " does not retry");
        }
        MODE.set("OK");
        System.out.println("PASS upstream errors, mismatched error codes, redirects, malformed JSON, header/body timeouts, no retry");
    }

    private static void verifyPreviewRegression(HttpClient http, int port) throws Exception {
        int inventoryBefore = INVENTORY_CALLS.get();
        HttpResponse<String> response = post(http, port, "/api/orders/preview", "{\"ticketTierId\":31,\"quantity\":2}");
        check(response.statusCode() == 200, "existing Event preview HTTP success");
        check(number(data(json(response.body())), "totalAmount") == 398, "existing Event preview amount");
        check(EVENT_CALLS.get() == 1, "Event builder still resolves Event service");
        check(INVENTORY_CALLS.get() == inventoryBefore, "preview never reserves stock");
        System.out.println("PASS existing EventClient preview regression (199.00 x 2 = 398.00)");
    }

    private static void verifyReceived(String method, String path) {
        Received received = RECEIVED.remove();
        check(method.equals(received.method()) && path.equals(received.path()), "method and path " + path);
        check(received.trace() != null && received.trace().matches("[0-9a-f]{32}"), "direct client propagates trace");
    }

    private static StockReservationRequest request() {
        return new StockReservationRequest("ORDER_VERIFY", 2L, 31L, 2, EXPIRY);
    }

    private static HttpResponse<String> orderReserve(HttpClient http, int port, String body) throws Exception {
        return post(http, port, "/internal/orders/stock-reservations", body);
    }

    private static HttpResponse<String> post(HttpClient http, int port, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-Trace-Id", TRACE).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer inventoryStub(String name, ExecutorService executor) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/internal/stock-reservations", exchange -> {
            INVENTORY_CALLS.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            RECEIVED.add(new Received(name, exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("X-Trace-Id"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String mode = MODE.get();
            int status = switch (mode) {
                case "BAD_REQUEST", "WRONG_400_CODE" -> 400;
                case "NOT_FOUND", "WRONG_404_CODE" -> 404;
                case "CONFLICT", "WRONG_409_CODE" -> 409;
                case "SERVER_ERROR" -> 500;
                case "REDIRECT" -> 302;
                case "UNKNOWN_4XX" -> 422;
                default -> 200;
            };
            String state = switch (mode) {
                case "RESERVED", "SOLD", "RELEASED" -> mode;
                case "BAD_STATUS" -> "UNKNOWN";
                default -> path.endsWith("/confirm") ? "SOLD" : path.endsWith("/release") ? "RELEASED" : "RESERVED";
            };
            String id = mode.equals("WRONG_ID") ? "invalid" : mode.equals("MISMATCH_ID")
                    ? "22222222222222222222222222222222" : RESERVATION_ID;
            String data = "{\"reservationId\":\"" + id + "\",\"orderId\":\""
                    + (mode.equals("WRONG_ORDER") ? "OTHER_ORDER" : "ORDER_VERIFY") + "\",\"sessionId\":"
                    + (mode.equals("WRONG_SESSION") ? 99 : 2) + ",\"ticketTierId\":"
                    + (mode.equals("WRONG_TIER") ? 99 : 31) + ",\"quantity\":"
                    + (mode.equals("WRONG_QUANTITY") ? 3 : 2) + ",\"expiresAt\":\""
                    + (mode.equals("WRONG_EXPIRY") ? EXPIRY.plusSeconds(1) : EXPIRY) + "\",\"status\":\"" + state + "\"}";
            if (mode.equals("EMPTY_DATA")) data = "null";
            String code = switch (mode) {
                case "BAD_REQUEST" -> "BAD_REQUEST";
                case "NOT_FOUND" -> "RESOURCE_NOT_FOUND";
                case "CONFLICT" -> "CONFLICT";
                case "BAD_CODE" -> "ERROR";
                default -> "OK";
            };
            String body = "{\"code\":\"" + code
                    + "\",\"message\":\"stub\",\"data\":" + data + ",\"traceId\":\""
                    + exchange.getRequestHeaders().getFirst("X-Trace-Id") + "\"}";
            if (mode.equals("BAD_JSON")) body = "invalid-json";
            if (mode.equals("EMPTY_BODY")) body = "";
            if (mode.equals("DELAY")) {
                try { Thread.sleep(600); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            respond(exchange, status, body, mode.equals("BODY_DELAY"));
        });
        server.start();
        return server;
    }

    private static HttpServer eventStub(ExecutorService executor) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/internal/ticket-tiers/31/purchase-rule", exchange -> {
            EVENT_CALLS.incrementAndGet();
            check("GET".equals(exchange.getRequestMethod()), "EventClient method preserved");
            check(TRACE.equals(exchange.getRequestHeaders().getFirst("X-Trace-Id")), "Event trace preserved");
            respond(exchange, 200, "{\"code\":\"OK\",\"message\":\"success\",\"data\":{\"eventId\":1,"
                    + "\"sessionId\":2,\"ticketTierId\":31,\"ticketTierName\":\"verification\",\"price\":199.00,"
                    + "\"saleStartTime\":\"2000-01-01T00:00:00\",\"saleEndTime\":\"2099-01-01T00:00:00\","
                    + "\"purchaseLimit\":2},\"traceId\":\"" + TRACE + "\"}");
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        respond(exchange, status, body, false);
    }

    private static void respond(HttpExchange exchange, int status, String body, boolean delayBody) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            if (delayBody) {
                try { Thread.sleep(600); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            exchange.getResponseBody().write(bytes);
        } catch (IOException expectedWhenClientTimesOut) {
            // A delayed response intentionally arrives after the client has closed its connection.
        } finally {
            exchange.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String body) {
        return JSON.readValue(body, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> result) {
        return (Map<String, Object>) result.get("data");
    }

    private static int number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).intValue();
    }

    private static void expectReason(Runnable action, InventoryServiceCallException.Reason reason, String label) {
        try {
            action.run();
            throw new AssertionError(label + " accepted unexpectedly");
        } catch (InventoryServiceCallException expected) {
            check(expected.getReason() == reason, label + " reason");
        }
    }

    private static void expectIllegal(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label + " accepted unexpectedly");
        } catch (IllegalArgumentException expected) {
            check(true, label);
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
