import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.client.EventCallGuard;
import com.byy.ticket.order.client.EventClient;
import com.byy.ticket.order.client.exception.EventServiceCallException;
import com.byy.ticket.order.config.EventClientProperties;
import com.byy.ticket.order.config.EventResilienceProperties;
import com.byy.ticket.order.config.RestClientConfig;
import com.byy.ticket.order.web.filter.TraceIdFilter;
import com.byy.ticket.order.web.handler.GlobalExceptionHandler;
import com.byy.ticket.security.service.OrderEventCredential;
import com.byy.ticket.security.service.OrderEventCredentialProperties;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 真实HTTP和LoadBalancer，验证熔断、恢复、并发拒绝与响应契约；不需要数据库或Nacos。 */
public class EventResilienceVerification {
    static final AtomicReference<String> mode = new AtomicReference<>("OK");
    static final AtomicInteger calls = new AtomicInteger(), invalidHeaders = new AtomicInteger();
    static volatile CountDownLatch entered, release;
    static int checks, port;
    static final JsonMapper json = new JsonMapper();
    static final HttpClient http = HttpClient.newHttpClient();

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"})
    @Import({RestClientConfig.class, EventClient.class, EventCallGuard.class,
        Probe.class, TraceIdFilter.class, GlobalExceptionHandler.class})
    public static class App {
        /** 仅验证夹具的HTTP探针放行；不改生产Security配置。 */
        @Bean SecurityFilterChain security(HttpSecurity builder) throws Exception {
            return builder.csrf(csrf -> csrf.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
        @Bean OrderEventCredential credential() throws Exception {
            return new OrderEventCredential(new OrderEventCredentialProperties("a".repeat(64), null));
        }
    }

    @RestController
    public static class Probe {
        final EventCallGuard guard;
        public Probe(EventCallGuard guard) { this.guard = guard; }
        @GetMapping("/verify/rule/{id}")
        public Result<?> get(@PathVariable("id") Long id) { return Result.success(guard.getPurchaseRule(id)); }
    }

    public static void main(String[] args) throws Exception {
        var workers = Executors.newFixedThreadPool(8);
        var stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(workers);
        stub.createContext("/internal/ticket-tiers/3/purchase-rule", exchange -> {
            calls.incrementAndGet();
            if (!"a".repeat(64).equals(exchange.getRequestHeaders().getFirst(OrderEventCredential.HEADER))
                || exchange.getRequestHeaders().getFirst("X-Trace-Id") == null) invalidHeaders.incrementAndGet();
            String current = mode.get();
            int status = switch(current) { case "FAIL" -> 500; case "MISSING" -> 404; case "REJECTED" -> 400; default -> 200; };
            String body = current.equals("BAD_JSON") ? "{broken" : "{\"code\":\"OK\",\"data\":{\"eventId\":1,\"sessionId\":2,"
                + "\"ticketTierId\":3,\"ticketTierName\":\"普通票\",\"price\":199.00,"
                + "\"saleStartTime\":\"2020-01-01T00:00:00\",\"saleEndTime\":\"2030-01-01T00:00:00\",\"purchaseLimit\":2}}";
            try {
                if(current.equals("HOLD")) { entered.countDown(); release.await(3, TimeUnit.SECONDS); }
                if(current.equals("TIMEOUT")) Thread.sleep(600);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception ignored) { /* 客户端超时后断开属于本测试预期。 */ }
            finally { exchange.close(); }
        });
        stub.start();
        try (var context = SpringApplication.run(App.class,
            "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
            "--spring.cloud.nacos.discovery.enabled=false",
            "--ticket.clients.event.connect-timeout=200ms", "--ticket.clients.event.read-timeout=250ms",
            "--ticket.clients.event.resilience.sliding-window-size=4",
            "--ticket.clients.event.resilience.minimum-number-of-calls=4",
            "--ticket.clients.event.resilience.open-wait=250ms",
            "--ticket.clients.event.resilience.half-open-calls=2",
            "--ticket.clients.event.resilience.half-open-max-wait=500ms",
            "--ticket.clients.event.resilience.max-concurrent-calls=2",
            "--spring.cloud.discovery.client.simple.instances.ticket-event-service[0].uri=http://127.0.0.1:" + stub.getAddress().getPort())) {
            port = ((WebServerApplicationContext)context).getWebServer().getPort();
            var guard = context.getBean(EventCallGuard.class);
            var breaker = guard.circuitBreaker();
            status(200); check(calls.get()==1, "normal request sends one HTTP call");
            check(breaker.getMetrics().getNumberOfSuccessfulCalls()==1, "success recorded");
            breaker.reset(); status(200); status(200); mode.set("FAIL"); status(503); status(503);
            check(breaker.getState()==CircuitBreaker.State.OPEN, "exactly 50 percent failures opens circuit");
            breaker.reset();
            mode.set("FAIL");
            for(int i=0;i<3;i++) status(503);
            check(breaker.getState()==CircuitBreaker.State.CLOSED, "minimum sample count respected");
            mode.set("MISSING"); for(int i=0;i<5;i++) status(404);
            mode.set("REJECTED"); for(int i=0;i<5;i++) status(502);
            check(breaker.getMetrics().getNumberOfBufferedCalls()==3, "404 and remote 4xx excluded from statistics");
            int before = calls.get(); status(400,0);
            check(calls.get()==before, "invalid argument makes no remote request");
            mode.set("FAIL"); status(503);
            check(breaker.getState()==CircuitBreaker.State.OPEN, "failure threshold opens circuit");
            before=calls.get(); var refused=status(503);
            check(calls.get()==before, "open circuit does not send HTTP");
            check(json.readTree(refused.body()).path("code").asText().equals("UPSTREAM_UNAVAILABLE"), "rejection preserves 503 code");
            check(json.readTree(refused.body()).path("traceId").asText().equals(refused.headers().firstValue("X-Trace-Id").orElse("")), "rejection trace consistent");
            mode.set("OK"); Thread.sleep(300); status(200);
            check(breaker.getState()==CircuitBreaker.State.HALF_OPEN, "recovery first probe is half open");
            status(200); check(breaker.getState()==CircuitBreaker.State.CLOSED, "successful probes close circuit");
            breaker.reset(); mode.set("FAIL"); for(int i=0;i<4;i++) status(503);
            Thread.sleep(300); status(503); status(503);
            check(breaker.getState()==CircuitBreaker.State.OPEN, "failed probes reopen circuit");
            breaker.reset(); mode.set("BAD_JSON"); status(502);
            check(breaker.getMetrics().getNumberOfFailedCalls()==1, "malformed response is a failure");
            breaker.reset(); mode.set("TIMEOUT"); status(504);
            check(breaker.getMetrics().getNumberOfFailedCalls()==1, "HTTP timeout is a failure");
            Thread.sleep(650); breaker.reset(); mode.set("HOLD");
            entered = new CountDownLatch(2); release = new CountDownLatch(1);
            var first=workers.submit(()->request(3)); var second=workers.submit(()->request(3));
            try {
                check(entered.await(2,TimeUnit.SECONDS), "two concurrent remote calls occupied permits");
                before=calls.get(); var full=status(503);
                check(calls.get()==before, "bulkhead rejects before HTTP");
                check(breaker.getMetrics().getNumberOfFailedCalls()==0, "bulkhead rejection does not count as Event failure");
                check(full.body().contains("活动查询繁忙"), "bulkhead rejection distinguished");
            } finally { release.countDown(); }
            check(first.get().statusCode()==200 && second.get().statusCode()==200, "in-flight calls still succeed");
            check(guard.bulkhead().getMetrics().getAvailableConcurrentCalls()==2, "concurrency permits returned");
            mode.set("OK"); status(200);
            // 不额外依赖一个管理端点；直接核对每个本地保护实例的独立状态。
            var disabled=new EventCallGuard(context.getBean(EventClient.class), new EventResilienceProperties(false,4,4,50,
                Duration.ofMillis(250),2,Duration.ofMillis(500),2));
            disabled.circuitBreaker().transitionToOpenState(); before=calls.get(); disabled.getPurchaseRule(3L);
            check(calls.get()==before+1, "disabled protection bypasses breaker");
            check(breaker.getState()==CircuitBreaker.State.CLOSED, "different guards do not share breaker state");
            var missing=new EventClient(context.getBean("eventRestClientBuilder",RestClient.Builder.class),
                new EventClientProperties("missing-event-verification",Duration.ofMillis(200),Duration.ofMillis(250)),
                context.getBean(OrderEventCredential.class));
            var missingGuard=new EventCallGuard(missing,context.getBean(EventResilienceProperties.class));
            try { missingGuard.getPurchaseRule(3L); throw new AssertionError("missing instance should fail"); }
            catch(EventServiceCallException expected) { check(expected.getReason()==EventServiceCallException.Reason.UNAVAILABLE, "missing instance mapped"); }
            check(missingGuard.circuitBreaker().getMetrics().getNumberOfFailedCalls()==1, "missing instance counted");
            check(invalidHeaders.get()==0, "trace and service credentials preserved");
            breaker.reset(); stub.stop(0); before=calls.get(); status(503);
            check(calls.get()==before && breaker.getMetrics().getNumberOfFailedCalls()==1, "connection refusal counted without phantom success");
            System.out.println("PASS Event resilience: " + checks + " checks; HTTP, failure/recovery and bulkhead verified.");
        } finally { stub.stop(0); workers.shutdownNow(); }
    }

    static HttpResponse<String> request(long id) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/verify/rule/"+id))
            .timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> status(int status) throws Exception { return status(status,3); }
    static HttpResponse<String> status(int status,long id) throws Exception {
        var response=request(id); check(response.statusCode()==status,"HTTP "+status+", received "+response.statusCode()); return response;
    }
    static void check(boolean condition,String message) { checks++; if(!condition)throw new AssertionError(message); }
}
