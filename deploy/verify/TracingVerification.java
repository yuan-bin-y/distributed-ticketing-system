import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** 真实 Gateway、Order、Event 和 Tempo 验证；沿用隔离库/会话夹具并清理自身资源。 */
public class TracingVerification extends EventAdministrationVerification {
    final String tempo = System.getenv().getOrDefault("TICKET_TEMPO_URL", "http://127.0.0.1:3200");
    TracingVerification(Path root) { super(root); }
    public static void main(String[] args) throws Exception {
        var test = new TracingVerification(Path.of(args[0]).toAbsolutePath());
        try { test.run(); } finally { test.cleanup(); }
        System.out.println("PASS tracing: " + test.checks + " checks; owned databases/sessions/processes cleaned.");
    }
    @Override void run() throws Exception {
        super.run();
        String token = loginAs(port(first), "buyer").path("accessToken").asText();
        long tier = number("SELECT id FROM " + eventSchema + ".t_ticket_tier WHERE preparation_status='READY' ORDER BY id LIMIT 1");
        String success = tracePreview(tier, token, 200);
        verifyTrace(success, false);
        String failure = tracePreview(Long.MAX_VALUE, token, 404);
        verifyTrace(failure, true);
        var malformed = request(Map.of("ticketTierId", tier, "quantity", 1), token,
                Map.of("traceparent", "invalid", "X-Trace-Id", "f".repeat(32)));
        status(malformed, 200);
        String fresh = malformed.headers().firstValue("X-Trace-Id").orElse("");
        check(fresh.matches("[0-9a-f]{32}") && !fresh.equals("f".repeat(32)),
                "invalid W3C context creates a new trace, legacy display header cannot replace it");
        check(JSON.readTree(malformed.body()).path("traceId").asText().equals(fresh), "fresh body/header trace match");
        verifyTrace(fresh, false);
        var evidence = Map.of("successTraceId", success, "failureTraceId", failure, "freshTraceId", fresh,
                "grafanaUrl", "http://localhost:3001/explore", "services", List.of("ticket-gateway", "ticket-order-service", "ticket-event-service"),
                "scope", "HTTP only; isolated business test databases cleaned");
        Files.writeString(root.resolve(".local/tracing-verification-result.json"), JSON.writeValueAsString(evidence));
        System.out.println("TRACE success=" + success + " failure=" + failure);
    }
    String tracePreview(long tier, String token, int expected) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        var response = request(Map.of("ticketTierId", tier, "quantity", 1), token,
                Map.of("traceparent", "00-" + id + "-1234567890abcdef-01", "X-Trace-Id", "f".repeat(32)));
        status(response, expected);
        check(response.headers().firstValue("X-Trace-Id").orElse("").equals(id), "Gateway response uses W3C trace ID");
        check(JSON.readTree(response.body()).path("traceId").asText().equals(id), "Order response body uses same trace ID");
        return id;
    }
    HttpResponse<String> request(Object body, String token, Map<String,String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + "/api/orders/preview"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    /** 查真实 Tempo，不用假 Span；检查三个服务的父子关系、耗时与错误状态。 */
    void verifyTrace(String id, boolean error) throws Exception {
        final List<Map.Entry<String,JsonNode>> spans = new ArrayList<>();
        await(() -> {
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(tempo + "/api/traces/" + id))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return false;
            if (response.statusCode() != 200) throw new IllegalStateException("Tempo query failed: " + response.statusCode());
            JsonNode trace = JSON.readTree(response.body());
            spans.clear();
            collect(trace, "", spans);
            // 三个进程分别批量导出，先到的可能只有 Security 子 Span；等待服务端和客户端一起到齐。
            var exported = new HashMap<String,Map.Entry<String,JsonNode>>();
            for (var entry : spans) exported.put(normalize(entry.getValue().path("spanId").asText()), entry);
            return hasParent(exported, "ticket-order-service", "ticket-gateway")
                    && hasParent(exported, "ticket-event-service", "ticket-order-service");
        }, "Tempo contains all three real services");
        var services = new HashSet<String>();
        var byId = new HashMap<String,Map.Entry<String,JsonNode>>();
        boolean foundError = false;
        for (var entry : spans) {
            var span = entry.getValue(); services.add(entry.getKey());
            check(normalize(span.path("traceId").asText()).equals(id), "exported trace ID matches response");
            String sid = normalize(span.path("spanId").asText());
            check(sid.matches("[0-9a-f]{16}"), "valid span ID");
            check(Long.parseUnsignedLong(span.path("endTimeUnixNano").asText()) >
                    Long.parseUnsignedLong(span.path("startTimeUnixNano").asText()), "positive measured duration");
            byId.put(sid, entry);
            if (span.path("status").path("code").asText().equals("STATUS_CODE_ERROR")
                    || span.path("status").path("code").asText().equals("2")) foundError = true;
        }
        check(services.containsAll(List.of("ticket-gateway", "ticket-order-service", "ticket-event-service")), "resource service names");
        check(hasParent(byId, "ticket-order-service", "ticket-gateway"), "Gateway to Order parent relation");
        check(hasParent(byId, "ticket-event-service", "ticket-order-service"), "Order to Event parent relation");
        // HTTP 404 是业务拒绝，OTel 服务端 Span 不一定标 ERROR；检查 HTTP 状态属性。
        if (error) check(foundError || spans.stream().anyMatch(e -> e.getValue().path("attributes").toString().contains("404")),
                "failed rule lookup exported with HTTP error evidence");
        Files.writeString(root.resolve(".local/trace-" + id + ".json"),
                JSON.writeValueAsString(spans.stream().map(e -> Map.of("service", e.getKey(), "span", e.getValue())).toList()));
    }
    boolean hasParent(Map<String,Map.Entry<String,JsonNode>> spans, String childService, String parentService) {
        return spans.values().stream().filter(e -> e.getKey().equals(childService)).anyMatch(e -> {
            var parent = spans.get(normalize(e.getValue().path("parentSpanId").asText()));
            return parent != null && parent.getKey().equals(parentService);
        });
    }
    String normalize(String value) {
        if (value.isEmpty() || value.matches("[0-9a-fA-F]{16}|[0-9a-fA-F]{32}")) return value.toLowerCase(Locale.ROOT);
        return HexFormat.of().formatHex(Base64.getDecoder().decode(value));
    }
    void collect(JsonNode node, String service, List<Map.Entry<String,JsonNode>> spans) {
        if (node.isArray()) { for (JsonNode child : node) collect(child, service, spans); return; }
        if (!node.isObject()) return;
        for (JsonNode attribute : node.path("resource").path("attributes"))
            if (attribute.path("key").asText().equals("service.name")) service = attribute.path("value").path("stringValue").asText();
        if (node.has("spanId") && node.has("traceId")) { spans.add(Map.entry(service, node)); return; }
        var properties = node.properties().iterator();
        while (properties.hasNext()) collect(properties.next().getValue(), service, spans);
    }
}
