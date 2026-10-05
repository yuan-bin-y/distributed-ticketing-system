import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** 独立订单/支付进程与真实MySQL验证；订单为已预留的持久化夹具，支付响应代理注入网络故障。 */
public class OrderPaymentVerification {
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TRACE = "a".repeat(32);
    final Path root, logs;
    final String suffix = UUID.randomUUID().toString().replace("-","");
    final String orderSchema="ticket_order_pay_verify_"+suffix, paymentSchema="ticket_payment_chain_verify_"+suffix;
    final List<String> createdSchemas=new ArrayList<>();
    final List<Process> children=new ArrayList<>();
    final ExecutorService workers=Executors.newCachedThreadPool();
    final AtomicReference<String> mode=new AtomicReference<>("PASS");
    final AtomicInteger paymentCalls=new AtomicInteger();
    final ConcurrentLinkedQueue<String> traces=new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> bodies=new ConcurrentLinkedQueue<>();
    Connection admin, orderDb, paymentDb;
    HttpServer proxy;
    int orderPort,paymentPort,proxyPort,checks,serial;
    String user,password;

    OrderPaymentVerification(Path root) throws Exception {
        this.root=root;
        logs=root.resolve(".local/order-payment-verification").resolve(suffix);
        Files.createDirectories(logs);
    }
    public static void main(String[] args) throws Exception {
        var test=new OrderPaymentVerification(Path.of(args[0]).toAbsolutePath());
        try {test.run();} finally {test.cleanup();}
        System.out.println("PASS order payment creation: "+test.checks
                +" checks; isolated databases and owned processes removed.");
    }
    void run() throws Exception {
        user=requireEnv("LOCAL_MYSQL_USERNAME");password=requireEnv("LOCAL_MYSQL_PASSWORD");
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",user,password);
        try(var sql=admin.createStatement()) {
            for(String schema:List.of(orderSchema,paymentSchema)) {
                sql.execute("CREATE DATABASE "+schema);createdSchemas.add(schema);
            }
        }
        orderDb=DriverManager.getConnection(dbUrl(orderSchema),user,password);
        paymentDb=DriverManager.getConnection(dbUrl(paymentSchema),user,password);
        paymentPort=freePort();
        Process pay=start("ticket-payment-service",paymentPort,paymentSchema,List.of(
                "--ticket.payment.simulation-enabled=true","--ticket.payment.dev-identity-enabled=true",
                "--ticket.payment.notification.enabled=false"));
        waitPing(pay,paymentPort,"/api/payments/ping");
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        proxy.setExecutor(workers);
        proxy.createContext("/",this::proxyRequest);
        proxy.start();proxyPort=proxy.getAddress().getPort();
        orderPort=freePort();
        Process order=startOrder(orderPort,false);
        waitPing(order,orderPort,"/api/orders/ping");
        normalAndRetry();
        guards();
        concurrentCreation();
        faults();
        lostResponse();
        closeDuringCall();
        alreadyPaid();
        int missingPort=freePort();
        Process missing=startOrder(missingPort,true);
        waitPing(missing,missingPort,"/api/orders/ping");
        String target=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        var result=post(missingPort,target,1L,null);
        check(result.statusCode()==503&&code(result).equals("UPSTREAM_UNAVAILABLE"),"no discovered payment instance returns503");
        check(countPayment(target)==0,"no instance leaves no payment");
    }

    void normalAndRetry() throws Exception {
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        var first=post(orderPort,order,1L,"{\"amount\":0.01,\"userId\":999}");
        check(first.statusCode()==200,"order invokes real payment creation");
        var data=data(first);
        String number=data.path("paymentNo").asString();
        check(number.matches("[0-9a-f]{32}")&&data.path("paymentStatus").asString().equals("CREATED"),"returns created payment");
        check(data.path("amount").decimalValue().compareTo(new java.math.BigDecimal("398.00"))==0,
                "public request cannot override saved amount");
        JsonNode sent=JSON.readTree(bodies.peek());
        check(sent.path("userId").asLong()==1&&sent.path("amount").decimalValue()
                .compareTo(new java.math.BigDecimal("398.00"))==0,"remote payload comes from order snapshot");
        check(traces.peek().equals(TRACE)&&first.headers().firstValue("X-Trace-Id").orElse("").equals(TRACE),
                "trace propagated order to payment and response");
        check(JSON.readTree(first.body()).path("traceId").asString().equals(TRACE),"Result trace propagated");
        var repeated=post(orderPort,order,1L,null);
        check(data(repeated).path("paymentNo").asString().equals(number),"repeat returns original payment number");
        check(countPayment(order)==1&&orderStatus(order).equals("PENDING_PAYMENT"),"one payment and unchanged order");
        check(paymentNo(order).equals(number),"persisted payment matches response");
        System.out.println("PASS real creation, saved amount/user, stable retry and tracing");
    }
    void guards() throws Exception {
        int before=paymentCalls.get();
        String owned=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        check(post(orderPort,owned,null,null).statusCode()==401,"missing identity rejected");
        check(post(orderPort,owned,2L,null).statusCode()==404,"foreign order hidden");
        check(post(orderPort,newNo(),1L,null).statusCode()==404,"missing order rejected");
        check(post(orderPort,"bad",1L,null).statusCode()==400,"bad order number rejected");
        check(post(orderPort,fixture("PENDING_PAYMENT",1L,true,now().minusSeconds(1)),1L,null).statusCode()==409,
                "expired order rejected");
        check(post(orderPort,fixture("PENDING_PAYMENT",1L,false,now().plusMinutes(10)),1L,null).statusCode()==409,
                "missing reservation rejected");
        for(String state:List.of("STOCK_PENDING","CLOSING","CLOSED","CREATE_FAILED","REVIEW_REQUIRED")) {
            String order=fixture(state,1L,true,now().plusMinutes(10));
            check(post(orderPort,order,1L,null).statusCode()==409,"state "+state+" rejected");
            check(countPayment(order)==0,"rejected state "+state+" creates no payment");
        }
        check(paymentCalls.get()==before,"local guards do not call payment");
        var get=request("GET",orderPort,"/api/orders/"+owned+"/payments",1L,null);
        check(get.statusCode()==405,"create-payment requires POST");
        System.out.println("PASS identity, ownership, expiry, state and reservation guards");
    }
    void concurrentCreation() throws Exception {
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        var ready=new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures=new ArrayList<>();
        for(int i=0;i<12;i++)futures.add(workers.submit(() -> {ready.await();return post(orderPort,order,1L,null);}));
        ready.countDown();
        Set<String> ids=new HashSet<>();
        for(var future:futures) {
            var result=future.get(30,TimeUnit.SECONDS);
            check(result.statusCode()==200,"concurrent order payment request succeeds");
            ids.add(data(result).path("paymentNo").asString());
        }
        check(ids.size()==1&&countPayment(order)==1,"12 concurrent requests create one payment");
        check(orderStatus(order).equals("PENDING_PAYMENT"),"concurrent creation does not advance order");
        System.out.println("PASS concurrent real order-to-payment creation");
    }
    void faults() throws Exception {
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        // 先建立记录以减少第一次框架类加载对短读取超时的影响。
        check(post(orderPort,order,1L,null).statusCode()==200,"fault fixture payment created");
        Map<String,Integer> cases=new LinkedHashMap<>();
        cases.put("400",400);cases.put("409",409);cases.put("503",503);
        for(String bad:List.of("400_WRONG","409_WRONG","404","401","REDIRECT","EMPTY","JSON",
                "BAD_CODE","BAD_ORDER","BAD_USER","BAD_AMOUNT","BAD_EXPIRY","BAD_NUMBER",
                "BAD_STATE","BAD_NOTIFY","MISSING_CREATED","PARTIAL_REVERSAL"))cases.put(bad,502);
        cases.put("HEAD_TIMEOUT",504);cases.put("BODY_TIMEOUT",504);
        for(var entry:cases.entrySet()) {
            mode.set(entry.getKey());
            int before=paymentCalls.get();
            var result=post(orderPort,order,1L,null);
            check(result.statusCode()==entry.getValue(),"fault "+entry.getKey()+" maps to "+entry.getValue());
            check(paymentCalls.get()==before+1,"fault "+entry.getKey()+" never automatically retries");
            check(orderStatus(order).equals("PENDING_PAYMENT"),"fault "+entry.getKey()+" leaves order unchanged");
        }
        mode.set("PASS");
        System.out.println("PASS business errors, unavailable, timeouts, redirects and malformed/mismatched contracts");
    }
    void lostResponse() throws Exception {
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        mode.set("LOST");
        int before=paymentCalls.get();
        var lost=post(orderPort,order,1L,null);
        check(lost.statusCode()==504,"lost committed response reports timeout");
        check(countPayment(order)==1,"payment committed despite timeout");
        check(paymentCalls.get()==before+1,"lost response makes one HTTP attempt");
        String original=paymentNo(order);
        mode.set("PASS");
        var retry=post(orderPort,order,1L,null);
        check(retry.statusCode()==200&&data(retry).path("paymentNo").asString().equals(original),
                "same order retries committed payment");
        check(countPayment(order)==1&&orderStatus(order).equals("PENDING_PAYMENT"),
                "lost response retry creates no duplicate and does not pay order");
        System.out.println("PASS committed-but-lost payment response and original-order retry");
    }
    void closeDuringCall() throws Exception {
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        mode.set("CLOSE");
        var result=post(orderPort,order,1L,null);
        check(result.statusCode()==409,"post-call recheck rejects order closed during HTTP");
        check(orderStatus(order).equals("CLOSING"),"payment creation does not revert concurrent closing");
        check(countPayment(order)==1,"already-created unpaid payment is preserved");
        mode.set("PASS");
        System.out.println("PASS post-call order-state recheck");
    }
    void alreadyPaid() throws Exception {
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        var created=post(orderPort,order,1L,null);
        String number=data(created).path("paymentNo").asString();
        var paid=request("POST",paymentPort,"/api/payments/"+number+"/simulate-success",1L,null);
        check(paid.statusCode()==200,"real payment independently simulates success");
        var repeated=post(orderPort,order,1L,null);
        check(repeated.statusCode()==200&&data(repeated).path("paymentStatus").asString().equals("SUCCESS"),
                "create retry preserves existing SUCCESS payment fact");
        check(orderStatus(order).equals("PENDING_PAYMENT"),"payment success does not falsely mark order PAID");
        var reversal=request("POST",paymentPort,"/internal/payment-reversals",null,
                "{\"paymentNo\":\""+number+"\",\"reason\":\"test closed\"}");
        check(reversal.statusCode()==200,"real payment reverses once");
        var reversed=post(orderPort,order,1L,null);
        check(reversed.statusCode()==200&&data(reversed).path("reversalStatus").asString().equals("SUCCESS"),
                "existing reversal fact survives payment creation retry");
        System.out.println("PASS existing payment success/reversal responses and unchanged order state");
    }

    void proxyRequest(HttpExchange exchange) throws java.io.IOException {
        paymentCalls.incrementAndGet();
        traces.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
        String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        bodies.add(body);
        String fault=mode.get();
        try {
            if(fault.equals("HEAD_TIMEOUT")) {Thread.sleep(1600);send(exchange,200,"{}",null);return;}
            if(Set.of("400","409","503","400_WRONG","409_WRONG","404","401").contains(fault)) {
                int status=Integer.parseInt(fault.substring(0,3));
                String code=status==400?"BAD_REQUEST":status==409?"CONFLICT":"RESOURCE_NOT_FOUND";
                if(fault.endsWith("WRONG"))code="INTERNAL_ERROR";
                send(exchange,status,"{\"code\":\""+code+"\",\"message\":\"stub\",\"data\":null}",null);return;
            }
            if(fault.equals("REDIRECT")) {send(exchange,302,"","http://127.0.0.1:"+paymentPort+"/internal/payments");return;}
            if(fault.equals("EMPTY")) {send(exchange,204,"",null);return;}
            if(fault.equals("JSON")) {send(exchange,200,"{broken",null);return;}
            var response=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+paymentPort+"/internal/payments"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                    .header("X-Trace-Id",TRACE).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if(fault.equals("LOST")) {Thread.sleep(1600);send(exchange,response.statusCode(),response.body(),null);return;}
            if(fault.equals("CLOSE")) {
                String order=JSON.readTree(body).path("orderNo").asString();
                try(var connection=DriverManager.getConnection(dbUrl(orderSchema),user,password);
                    var update=connection.prepareStatement("UPDATE t_order SET status='CLOSING' WHERE order_no=?")) {
                    update.setString(1,order);update.executeUpdate();
                }
            }
            if(fault.equals("BODY_TIMEOUT")) {
                byte[] bytes=response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write(bytes,0,10);exchange.getResponseBody().flush();
                Thread.sleep(1600);exchange.getResponseBody().write(bytes,10,bytes.length-10);return;
            }
            ObjectNode tree=(ObjectNode)JSON.readTree(response.body());
            ObjectNode data=(ObjectNode)tree.path("data");
            switch(fault) {
                case "BAD_CODE" -> tree.put("code","FAILED");
                case "BAD_ORDER" -> data.put("orderNo",newNo());
                case "BAD_USER" -> data.put("userId",999);
                case "BAD_AMOUNT" -> data.put("amount",new java.math.BigDecimal("1.00"));
                case "BAD_EXPIRY" -> data.put("expiresAt",LocalDateTime.parse(data.path("expiresAt").asString()).plusSeconds(1).toString());
                case "BAD_NUMBER" -> data.put("paymentNo","invalid");
                case "BAD_STATE" -> data.put("status","PAID");
                case "BAD_NOTIFY" -> data.put("notifyStatus","PENDING");
                case "MISSING_CREATED" -> data.putNull("createdAt");
                case "PARTIAL_REVERSAL" -> data.put("reversalNo",newNo());
            }
            send(exchange,response.statusCode(),JSON.writeValueAsString(tree),null);
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
          catch(Exception failure) {
            // 超时故障中客户端主动关闭连接是预期结果；其他异常留在自身验证日志。
            if(!(failure instanceof java.io.IOException))failure.printStackTrace();
        } finally {exchange.close();}
    }
    void send(HttpExchange exchange,int status,String body,String redirect) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type","application/json");
        if(redirect!=null)exchange.getResponseHeaders().set("Location",redirect);
        if(status==204){exchange.sendResponseHeaders(status,-1);return;}
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status,bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    Process startOrder(int port,boolean missing) throws Exception {
        return start("ticket-order-service",port,orderSchema,List.of(
                "--ticket.order.dev-identity-enabled=true","--ticket.order.recovery-enabled=false",
                "--ticket.clients.payment.service-id="+(missing?"no-payment-instance":"ticket-payment-service"),
                "--ticket.clients.payment.connect-timeout=1s","--ticket.clients.payment.read-timeout=700ms",
                "--spring.cloud.discovery.client.simple.instances.ticket-payment-service[0].uri=http://127.0.0.1:"+proxyPort));
    }
    Process start(String module,int port,String schema,List<String> extra) throws Exception {
        Path artifact=root.resolve(module+"/target/"+module+"-1.0-SNAPSHOT.jar");
        List<String> args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
                "-jar",artifact.toString(),"--server.port="+port,"--spring.cloud.nacos.discovery.enabled=false",
                "--spring.main.banner-mode=off","--logging.level.root=ERROR","--spring.datasource.url="+dbUrl(schema)));
        args.addAll(extra);
        Process process=new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(logs.resolve(module+"_"+serial+++".log").toFile()).start();
        children.add(process);return process;
    }
    void waitPing(Process process,int port,String path) throws Exception {
        for(int i=0;i<160;i++) {
            if(!process.isAlive())throw new IllegalStateException("Service exited; inspect "+logs);
            try {
                if(request("GET",port,path,null,null).statusCode()==200)return;
            } catch(java.io.IOException expected) {}
            Thread.sleep(150);
        }
        throw new IllegalStateException("Service startup timed out; inspect "+logs);
    }
    String fixture(String status,Long user,boolean reservation,LocalDateTime expires) throws Exception {
        String no=newNo();
        try(var sql=orderDb.prepareStatement("INSERT INTO t_order(order_no,user_id,idempotency_key,total_amount,status,"
                +"reservation_id,expires_at,next_attempt_at) VALUES(?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
            sql.setString(1,no);sql.setLong(2,user);sql.setString(3,no);sql.setBigDecimal(4,new java.math.BigDecimal("398.00"));
            sql.setString(5,status);sql.setString(6,reservation?newNo():null);
            sql.setTimestamp(7,Timestamp.valueOf(expires));sql.setTimestamp(8,Timestamp.valueOf(expires));sql.executeUpdate();
            try(var keys=sql.getGeneratedKeys()) {
                keys.next();long id=keys.getLong(1);
                try(var item=orderDb.prepareStatement("INSERT INTO t_order_item(order_id,event_id,session_id,ticket_tier_id,"
                        +"ticket_tier_name,unit_price,quantity,subtotal_amount) VALUES(?,1,1,1,'fixture',199.00,2,398.00)")) {
                    item.setLong(1,id);item.executeUpdate();
                }
            }
        }
        return no;
    }
    HttpResponse<String> post(int port,String order,Long user,String body) throws Exception {
        return request("POST",port,"/api/orders/"+order+"/payments",user,body);
    }
    HttpResponse<String> request(String method,int port,String path,Long user,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15))
                .header("X-Trace-Id",TRACE);
        if(user!=null)builder.header("X-Dev-User-Id",user.toString());
        if(path.equals("/internal/orders/payment-results"))builder.header("X-Payment-Order-Credential",serviceCredential());
        if(body!=null)builder.header("Content-Type","application/json");
        return HTTP.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():
                HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response){return JSON.readTree(response.body()).path("data");}
    /** 旧交易回归改用显式通知服务凭证，不再匿名调用新受保护入口。 */
    String serviceCredential() throws Exception {
        String configured=System.getenv("PAYMENT_ORDER_SERVICE_TOKEN");
        if(configured!=null&&!configured.isBlank())return configured;
        String path=System.getenv("PAYMENT_ORDER_CREDENTIAL_PATH");
        return Files.readString(path==null||path.isBlank()?root.resolve(".local/service-credentials/payment-order.token"):Path.of(path)).strip();
    }
    String code(HttpResponse<String> response){return JSON.readTree(response.body()).path("code").asString();}
    String orderStatus(String no) throws Exception {
        try(var sql=orderDb.prepareStatement("SELECT status FROM t_order WHERE order_no=?")) {
            sql.setString(1,no);try(var result=sql.executeQuery()){result.next();return result.getString(1);}
        }
    }
    int countPayment(String no) throws Exception {
        try(var sql=paymentDb.prepareStatement("SELECT COUNT(*) FROM t_payment WHERE order_no=?")) {
            sql.setString(1,no);try(var result=sql.executeQuery()){result.next();return result.getInt(1);}
        }
    }
    String paymentNo(String no) throws Exception {
        try(var sql=paymentDb.prepareStatement("SELECT payment_no FROM t_payment WHERE order_no=?")) {
            sql.setString(1,no);try(var result=sql.executeQuery()){result.next();return result.getString(1);}
        }
    }
    void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    String newNo(){return UUID.randomUUID().toString().replace("-","");}
    LocalDateTime now(){return LocalDateTime.now(ZoneOffset.ofHours(8)).truncatedTo(ChronoUnit.MILLIS);}
    int freePort() throws Exception {try(var socket=new ServerSocket(0)){return socket.getLocalPort();}}
    String dbUrl(String schema){return "jdbc:mysql://127.0.0.1:3306/"+schema
            +"?connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true";}
    static String requireEnv(String key) {
        String value=System.getenv(key);
        if(value==null||value.isBlank())throw new IllegalStateException("Missing environment: "+key);
        return value;
    }
    void cleanup() throws Exception {
        for(Process child:children) {
            if(child.isAlive())child.destroy();
            if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}
        }
        if(proxy!=null)proxy.stop(0);
        workers.shutdownNow();workers.awaitTermination(10,TimeUnit.SECONDS);
        if(orderDb!=null)orderDb.close();if(paymentDb!=null)paymentDb.close();
        if(admin!=null) {
            try(var sql=admin.createStatement()) {
                for(String schema:createdSchemas) {
                    if(!schema.matches("ticket_(order_pay|payment_chain|stock_pay)_verify_[0-9a-f]{32}"))
                        throw new IllegalStateException("Unsafe cleanup schema");
                    sql.execute("DROP DATABASE "+schema);
                }
            } finally {admin.close();}
        }
    }
}
