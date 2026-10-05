import com.byy.ticket.payment.TicketPaymentApplication;
import com.byy.ticket.payment.dto.payment.*;
import com.byy.ticket.payment.exception.*;
import com.byy.ticket.payment.service.PaymentService;
import com.byy.ticket.payment.vo.payment.*;
import com.byy.ticket.common.exception.ResourceNotFoundException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 真实MySQL、行锁并发、事务故障与HTTP契约验证；只创建并删除随机测试库。 */
public class PaymentVerification {
    static final MutableClock CLOCK = new MutableClock();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static final JsonMapper JSON = JsonMapper.builder().build();
    final String suffix = UUID.randomUUID().toString().replace("-", "");
    final String schema = "ticket_payment_verify_" + suffix;
    final String group = "payment_verify_" + suffix;
    final ExecutorService workers = Executors.newFixedThreadPool(20);
    Connection admin;
    JdbcTemplate jdbc;
    PaymentService service;
    int port, passed;
    String durableOrder;
    boolean schemaCreated;

    public static void main(String[] args) throws Exception {
        var test = new PaymentVerification();
        try { test.run(Arrays.asList(args).contains("--verify-nacos")); }
        finally { test.cleanup(); }
        System.out.println("PASS payment verification: " + test.passed
                + " checks; isolated database removed.");
    }

    void run(boolean verifyNacos) throws Exception {
        String user = requireEnv("LOCAL_MYSQL_USERNAME"), password = requireEnv("LOCAL_MYSQL_PASSWORD");
        admin = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/", user, password);
        try (Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            schemaCreated = true;
        }
        try (var context = start(false, false)) {
            bind(context);
            var payment = service.create(request(newNo(), "398.00", future()));
            expect(ResourceNotFoundException.class, () -> service.simulateSuccess(1L, payment.paymentNo()),
                    "simulation disabled by default");
            expect(ResourceNotFoundException.class,
                    () -> service.reverse(new PaymentReversalDTO(payment.paymentNo(), "closed")),
                    "reversal simulation disabled by default");
            check(http("POST", "/api/payments/" + payment.paymentNo() + "/simulate-success", null,
                    "X-Dev-User-Id", "1").statusCode() == 401, "dev identity disabled by default");
        }
        try (var context = start(true, false)) {
            bind(context);
            check(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class) == 2,
                    "Flyway migration validates on second start");
            createAndConflict();
            concurrentCreate();
            paymentAndExpiry();
            concurrentPayAndReverse();
            rollback();
            httpContract();
        }
        // 重启后读取原业务记录；可选同时验证真实Nacos注册，不写默认业务库。
        try (var context = start(true, verifyNacos)) {
            bind(context);
            var payment = service.getByOrder(durableOrder);
            check("SUCCESS".equals(payment.status()) && "SUCCESS".equals(payment.reversalStatus()),
                    "payment and reversal facts survive restart");
            if (verifyNacos) {
                String url = "http://127.0.0.1:8848/nacos/v1/ns/instance/list"
                        + "?serviceName=ticket-payment-service&groupName=" + group + "&healthyOnly=true";
                boolean registered = false;
                for (int i = 0; i < 20; i++) {
                    var response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        var hosts = JSON.readTree(response.body()).path("hosts");
                        for (JsonNode host : hosts) {
                            if (host.path("port").asInt() == port && host.path("healthy").asBoolean()) {
                                registered = true;
                            }
                        }
                    }
                    if (registered) break;
                    Thread.sleep(200);
                }
                check(registered, "real Nacos registers a healthy payment instance in an isolated group");
            }
        }
    }

    org.springframework.context.ConfigurableApplicationContext start(boolean enabled, boolean nacos) {
        var app = new SpringApplication(TicketPaymentApplication.class, ClockConfiguration.class);
        return app.run("--server.port=0", "--spring.main.banner-mode=off",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/" + schema
                        + "?connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true",
                "--spring.datasource.hikari.maximum-pool-size=25",
                "--ticket.payment.simulation-enabled=" + enabled,
                "--ticket.payment.dev-identity-enabled=" + enabled,
                "--ticket.payment.notification.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=" + nacos,
                "--spring.cloud.discovery.enabled=" + nacos,
                "--spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848",
                "--spring.cloud.nacos.discovery.group=" + group,
                "--spring.cloud.nacos.discovery.ip=127.0.0.1",
                "--logging.level.root=WARN");
    }

    void bind(org.springframework.context.ConfigurableApplicationContext context) {
        jdbc = context.getBean(JdbcTemplate.class);
        service = context.getBean(PaymentService.class);
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    void createAndConflict() {
        String order = newNo();
        var req = request(order, "398.00", future());
        var payment = service.create(req);
        check("CREATED".equals(payment.status()) && payment.paidAt() == null
                && "NONE".equals(payment.notifyStatus()), "new payment has no paid fact or pending notice");
        check(payment.paymentNo().equals(service.create(request(order, "398.0", req.expiresAt())).paymentNo()),
                "same numeric amount returns original payment");
        expect(PaymentConflictException.class,
                () -> service.create(new PaymentCreateDTO(order, 2L, req.amount(), req.expiresAt())), "changed user rejected");
        expect(PaymentConflictException.class,
                () -> service.create(request(order, "399.00", req.expiresAt())), "changed amount rejected");
        expect(PaymentConflictException.class,
                () -> service.create(request(order, "398.00", req.expiresAt().plusMinutes(1))), "changed expiry rejected");
        check(count("t_payment", "order_no", order) == 1, "conflicting requests do not create extra payments");
        expect(PaymentConflictException.class,
                () -> service.reverse(new PaymentReversalDTO(payment.paymentNo(), "closed")), "unpaid reversal rejected");
        expect(ResourceNotFoundException.class, () -> service.getOwned(2L, payment.paymentNo()), "foreign query hidden");
        expect(ResourceNotFoundException.class, () -> service.simulateSuccess(2L, payment.paymentNo()), "foreign pay hidden");
        String expired = newNo();
        expect(PaymentConflictException.class, () -> service.create(request(expired, "1.00", now())),
                "first creation at expiry rejected");
        check(count("t_payment", "order_no", expired) == 0, "expired creation rolls insert back");
        expect(IllegalArgumentException.class, () -> service.create(request(newNo(), "0", future())), "zero amount rejected");
        expect(IllegalArgumentException.class, () -> service.create(request(newNo(), "1.001", future())), "fraction truncated never");
        expect(IllegalArgumentException.class, () -> service.create(request(newNo(), "10000000000000000", future())),
                "amount overflow rejected");
        expect(IllegalArgumentException.class, () -> service.create(request(newNo(), "1.00", future().plusNanos(1))),
                "submillisecond expiry rejected");
        expect(IllegalArgumentException.class, () -> service.create(request(newNo(), "1.00", LocalDateTime.of(999,1,1,0,0))),
                "unsupported MySQL datetime rejected");
        expect(IllegalArgumentException.class, () -> service.create(request("bad", "1.00", future())), "bad order rejected");
        expect(IllegalArgumentException.class, () -> service.create(null), "null request rejected");
        expect(ResourceNotFoundException.class, () -> service.getByOrder(newNo()), "missing order query");
        expect(ResourceNotFoundException.class,
                () -> service.reverse(new PaymentReversalDTO(newNo(), "closed")), "missing reversal target");
        System.out.println("PASS creation, conflicts, ownership, amount/time validation");
    }

    void concurrentCreate() throws Exception {
        String order = newNo();
        var req = request(order, "199.00", future());
        var commands = new ArrayList<Callable<PaymentVO>>();
        for (int i=0;i<20;i++) commands.add(() -> service.create(req));
        Set<String> numbers = new HashSet<>();
        for (var value : race(commands)) numbers.add(value.paymentNo());
        check(numbers.size() == 1 && count("t_payment","order_no",order) == 1,
                "20 concurrent creation retries produce one payment");
        String conflicting = newNo();
        var commands2 = List.<Callable<Boolean>>of(
                () -> createOrConflict(request(conflicting, "10.00", req.expiresAt())),
                () -> createOrConflict(request(conflicting, "20.00", req.expiresAt())));
        check(race(commands2).stream().filter(Boolean::booleanValue).count() == 1,
                "different amounts racing on one order have one winner");
        check(count("t_payment","order_no",conflicting) == 1, "different payload race leaves one row");
        System.out.println("PASS concurrent unique-key creation and conflicting requests");
    }

    void paymentAndExpiry() {
        var req = request(newNo(), "100.00", now().plusSeconds(10));
        var payment = service.create(req);
        CLOCK.set(req.expiresAt().minusNanos(1_000_000));
        var paid = service.simulateSuccess(1L,payment.paymentNo());
        check("SUCCESS".equals(paid.status()) && "PENDING".equals(paid.notifyStatus()),
                "payment before expiry records success and pending notification");
        check(paid.paidAt().equals(now()), "paid time uses fixed millisecond clock");
        check(jdbc.queryForObject("SELECT next_notify_at=paid_at FROM t_payment WHERE payment_no=?",
                Boolean.class,payment.paymentNo()), "pending notification persisted with same paid time");
        jdbc.update("UPDATE t_payment SET notify_status='DELIVERED',notify_attempt_count=3,last_notify_error='preserved' WHERE payment_no=?",
                payment.paymentNo());
        CLOCK.set(req.expiresAt().plusSeconds(1));
        var repeated = service.simulateSuccess(1L,payment.paymentNo());
        check(repeated.paidAt().equals(paid.paidAt()) && "DELIVERED".equals(repeated.notifyStatus()),
                "successful retry after expiry preserves original paid time and delivery");
        check(jdbc.queryForObject("SELECT notify_attempt_count FROM t_payment WHERE payment_no=?",
                Integer.class,payment.paymentNo()) == 3, "successful retry does not reset attempts");
        check(payment.paymentNo().equals(service.create(req).paymentNo()), "expired creation retry returns original");
        var unpaidReq = request(newNo(), "10.00", now().plusSeconds(10));
        var unpaid = service.create(unpaidReq);
        CLOCK.set(unpaidReq.expiresAt());
        expect(PaymentConflictException.class, () -> service.simulateSuccess(1L,unpaid.paymentNo()),
                "first payment at exact deadline rejected");
        var unchanged = service.getByOrder(unpaidReq.orderNo());
        check("CREATED".equals(unchanged.status()) && unchanged.paidAt()==null
                && "NONE".equals(unchanged.notifyStatus()), "expiry rejection leaves no paid fact");
        System.out.println("PASS deadline boundary, durable notice, immutable successful retries");
    }

    void concurrentPayAndReverse() throws Exception {
        var req = request(newNo(),"398.00",future());
        var payment = service.create(req);
        var commands = new ArrayList<Callable<PaymentVO>>();
        for(int i=0;i<20;i++) commands.add(() -> service.simulateSuccess(1L,payment.paymentNo()));
        Set<LocalDateTime> times = new HashSet<>();
        for(var paid:race(commands)) times.add(paid.paidAt());
        check(times.size()==1, "20 concurrent success calls preserve one paid time");
        var reversalReq = new PaymentReversalDTO(payment.paymentNo(),"订单已关闭");
        var reversals = new ArrayList<Callable<PaymentReversalVO>>();
        for(int i=0;i<20;i++) reversals.add(() -> service.reverse(reversalReq));
        Set<String> ids = new HashSet<>();
        for(var reversal:race(reversals)) {
            ids.add(reversal.reversalNo());
            check("SUCCESS".equals(reversal.status())
                    && reversal.amount().compareTo(req.amount())==0, "full simulated reversal preserves amount");
        }
        check(ids.size()==1, "20 concurrent reversals return one reversal number");
        Long id=jdbc.queryForObject("SELECT id FROM t_payment WHERE payment_no=?",Long.class,payment.paymentNo());
        check(jdbc.queryForObject("SELECT COUNT(*) FROM t_payment_reversal WHERE payment_id=?",Integer.class,id)==1,
                "one reversal row per payment");
        expect(PaymentConflictException.class,
                () -> service.reverse(new PaymentReversalDTO(payment.paymentNo(),"不同原因")), "changed reversal reason rejected");
        var current=service.getByOrder(req.orderNo());
        check("SUCCESS".equals(current.status()) && "SUCCESS".equals(current.reversalStatus())
                && "PENDING".equals(current.notifyStatus()), "reversal preserves payment fact and notification");
        check("SUCCESS".equals(service.simulateSuccess(1L,payment.paymentNo()).reversalStatus()),
                "success retry after reversal does not charge again");
        durableOrder=req.orderNo();
        var other=service.create(request(newNo(),"1.00",future()));
        var values=race(List.<Callable<Boolean>>of(
                () -> {service.simulateSuccess(1L,other.paymentNo()); return true;},
                () -> {try {service.reverse(new PaymentReversalDTO(other.paymentNo(),"race"));return true;}
                       catch(PaymentConflictException expected){return false;}}));
        check(values.get(0), "simulated payment racing with reversal completes once");
        var finalReversal=service.reverse(new PaymentReversalDTO(other.paymentNo(),"race"));
        check("SUCCESS".equals(finalReversal.status()), "reversal can safely retry after payment race");
        System.out.println("PASS concurrent payment, reversal idempotence and payment/reversal competition");
    }

    void rollback() {
        var payment = service.create(request(newNo(),"20.00",future()));
        jdbc.execute("CREATE TRIGGER verify_fail_payment BEFORE UPDATE ON t_payment FOR EACH ROW"
                + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='verification payment failure'");
        try {
            expect(DataAccessException.class, () -> service.simulateSuccess(1L,payment.paymentNo()),
                    "injected payment write failure propagated");
        } finally { jdbc.execute("DROP TRIGGER verify_fail_payment"); }
        var current=service.getOwned(1L,payment.paymentNo());
        check("CREATED".equals(current.status()) && current.paidAt()==null && "NONE".equals(current.notifyStatus()),
                "failed payment update leaves neither paid fact nor pending notification");
        service.simulateSuccess(1L,payment.paymentNo());
        jdbc.execute("CREATE TRIGGER verify_fail_reversal BEFORE UPDATE ON t_payment_reversal FOR EACH ROW"
                + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='verification reversal failure'");
        try {
            expect(DataAccessException.class,
                    () -> service.reverse(new PaymentReversalDTO(payment.paymentNo(),"rollback")),
                    "injected reversal completion failure propagated");
        } finally { jdbc.execute("DROP TRIGGER verify_fail_reversal"); }
        Long id=jdbc.queryForObject("SELECT id FROM t_payment WHERE payment_no=?",Long.class,payment.paymentNo());
        check(jdbc.queryForObject("SELECT COUNT(*) FROM t_payment_reversal WHERE payment_id=?",Integer.class,id)==0,
                "failed reversal completion rolls preceding insert back");
        check("SUCCESS".equals(service.reverse(new PaymentReversalDTO(payment.paymentNo(),"rollback")).status()),
                "failed reversal retries successfully");
        System.out.println("PASS SQL fault injection and transaction rollback");
    }

    void httpContract() throws Exception {
        String order=newNo();
        String body=createJson(order,"38.00",future());
        var created=http("POST","/internal/payments",body,"X-Trace-Id","a".repeat(32));
        check(created.statusCode()==200, "HTTP creates payment");
        JsonNode tree=JSON.readTree(created.body());
        check("OK".equals(tree.path("code").asString()) && "a".repeat(32).equals(tree.path("traceId").asString()),
                "Result envelope carries original trace");
        check(created.headers().firstValue("X-Trace-Id").orElse("").equals("a".repeat(32)), "trace response header");
        String number=tree.path("data").path("paymentNo").asString();
        check(http("GET","/api/payments/"+number,null).statusCode()==401, "missing identity rejected");
        check(http("GET","/api/payments/"+number,null,"X-User-Id","1").statusCode()==401, "plain user header untrusted");
        check(http("GET","/api/payments/"+number,null,"X-Dev-User-Id","9223372036854775808").statusCode()==401,
                "overflow identity rejected");
        check(http("GET","/api/payments/"+number,null,"X-Dev-User-Id","2").statusCode()==404, "foreign HTTP query hidden");
        check(http("POST","/api/payments/"+number+"/simulate-success",null,"X-Dev-User-Id","2").statusCode()==404,
                "foreign HTTP pay rejected");
        check(http("POST","/api/payments/"+number+"/simulate-success","{\"amount\":0.01}","X-Dev-User-Id","1")
                .statusCode()==200, "owned HTTP simulated payment");
        check(service.getByOrder(order).amount().compareTo(new BigDecimal("38.00"))==0,
                "public success body cannot change amount");
        check(http("GET","/internal/payments/by-order/"+order,null).statusCode()==200, "internal HTTP payment query");
        check(http("POST","/internal/payments",createJson(order,"39.00",future())).statusCode()==409,
                "HTTP conflicting creation");
        check(http("POST","/internal/payments","{}").statusCode()==400, "HTTP validation rejects missing fields");
        check(http("POST","/internal/payments","{").statusCode()==400, "HTTP malformed JSON");
        check(http("GET","/internal/payments/by-order/"+newNo(),null).statusCode()==404, "HTTP absent payment");
        check(http("POST","/internal/payment-reversals",
                "{\"paymentNo\":\""+number+"\",\"reason\":\"closed\"}").statusCode()==200, "HTTP full reversal");
        check(http("POST","/internal/payment-reversals","{\"paymentNo\":\""+number+"\",\"reason\":\" \"}")
                .statusCode()==400, "HTTP empty reversal reason");
        check(http("DELETE","/api/payments/"+number,null,"X-Dev-User-Id","1").statusCode()==405, "HTTP wrong method");
        check(http("GET","/missing",null,"X-Dev-User-Id","1").statusCode()==404, "HTTP unknown path");
        check(http("GET","/api/payments/ping",null).body().contains("ticket-payment-service"), "payment ping");
        System.out.println("PASS HTTP contracts, identity gates, ownership and tracing");
    }

    HttpResponse<String> http(String method,String path,String body,String... headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(body!=null)builder.header("Content-Type","application/json");
        if(path.startsWith("/internal/payments")||path.equals("/internal/payment-reversals")){
            String secret=System.getenv("ORDER_PAYMENT_SERVICE_TOKEN");
            if(secret==null||secret.isBlank()){
                String file=System.getenv("ORDER_PAYMENT_CREDENTIAL_PATH");
                secret=java.nio.file.Files.readString(java.nio.file.Path.of(file==null||file.isBlank()?".local/service-credentials/order-payment.token":file)).strip();
            }
            builder.header("X-Order-Payment-Credential",secret);
        }
        for(int i=0;i<headers.length;i+=2)builder.header(headers[i],headers[i+1]);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    String createJson(String order,String amount,LocalDateTime expires) {
        return "{\"orderNo\":\""+order+"\",\"userId\":1,\"amount\":"+amount+",\"expiresAt\":\""+expires+"\"}";
    }
    boolean createOrConflict(PaymentCreateDTO request) {
        try {service.create(request);return true;}catch(PaymentConflictException expected){return false;}
    }
    <T> List<T> race(List<Callable<T>> commands) throws Exception {
        var start=new CountDownLatch(1);
        List<Future<T>> futures=new ArrayList<>();
        for(var command:commands)futures.add(workers.submit(() -> {start.await();return command.call();}));
        start.countDown();
        List<T> results=new ArrayList<>();
        for(var future:futures)results.add(future.get(30,TimeUnit.SECONDS));
        return results;
    }
    int count(String table,String column,String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?",Integer.class,key);
    }
    void check(boolean condition,String label) {
        if(!condition)throw new AssertionError(label);
        passed++;
    }
    void expect(Class<? extends Throwable> type,Runnable action,String label) {
        try {action.run();}catch(Throwable failure) {
            if(!type.isInstance(failure))throw new AssertionError(label+": "+failure.getClass().getSimpleName(),failure);
            passed++;return;
        }
        throw new AssertionError(label+": expected exception");
    }
    PaymentCreateDTO request(String order,String amount,LocalDateTime expiry) {
        return new PaymentCreateDTO(order,1L,new BigDecimal(amount),expiry);
    }
    LocalDateTime now(){return LocalDateTime.now(CLOCK).truncatedTo(ChronoUnit.MILLIS);}
    LocalDateTime future(){return now().plusMinutes(15);}
    String newNo(){return UUID.randomUUID().toString().replace("-","");}
    static String requireEnv(String key) {
        String value=System.getenv(key);
        if(value==null || value.isBlank())throw new IllegalStateException("Missing environment: "+key);
        return value;
    }
    void cleanup() throws Exception {
        workers.shutdownNow();
        workers.awaitTermination(30,TimeUnit.SECONDS);
        if(admin!=null) {
            try {
                if(schemaCreated) {
                    if(!schema.matches("ticket_payment_verify_[0-9a-f]{32}"))throw new IllegalStateException("Unsafe test schema");
                    try(var sql=admin.createStatement()){sql.execute("DROP DATABASE "+schema);}
                }
            } finally {admin.close();}
        }
    }
    @Configuration(proxyBeanMethods=false)
    static class ClockConfiguration {
        @Bean @Primary Clock verificationClock(){return CLOCK;}
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> instant=new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));
        void set(LocalDateTime time){instant.set(time.toInstant(ZoneOffset.ofHours(8)));}
        @Override public ZoneId getZone(){return ZoneOffset.ofHours(8);}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(instant.get(),zone);}
        @Override public Instant instant(){return instant.get();}
    }
}
