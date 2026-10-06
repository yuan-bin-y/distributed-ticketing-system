import com.byy.ticket.resilience.*;
import com.byy.ticket.order.client.*;
import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.config.*;
import com.byy.ticket.event.client.InventoryInitializationClient;
import com.byy.ticket.event.config.EventPreparationProperties;
import com.byy.ticket.payment.client.OrderClient;
import com.byy.ticket.payment.config.PaymentNotificationProperties;
import com.byy.ticket.security.service.*;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** 四条下游链路、九个实际HTTP操作，验证熔断、隔离和响应丢失，不启动业务数据库。 */
public class HttpResilienceVerification {
    static final String ORDER="a".repeat(32), PAYMENT="b".repeat(32), RESERVATION="c".repeat(32);
    static final String TOKEN="d".repeat(64), EXPIRY="2030-01-01T12:15:00";
    static final AtomicReference<String> mode=new AtomicReference<>("OK");
    static final AtomicInteger calls=new AtomicInteger(), commits=new AtomicInteger(), invalidHeaders=new AtomicInteger();
    static volatile CountDownLatch entered,release;
    static int checks;
    record Operation(String name,Runnable call,HttpCallProtection protection){}

    public static void main(String[] args)throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        var stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);stub.setExecutor(pool);
        var json=new JsonMapper();
        stub.createContext("/",exchange->{
            calls.incrementAndGet();String path=exchange.getRequestURI().getPath(),current=mode.get();
            boolean credential=exchange.getRequestHeaders().entrySet().stream().anyMatch(e->e.getKey().toLowerCase().endsWith("credential")&&e.getValue().contains(TOKEN));
            if(!credential||exchange.getRequestHeaders().getFirst("X-Trace-Id")==null)invalidHeaders.incrementAndGet();
            int status=switch(current){case "FAIL"->503;case "NOT_FOUND"->404;case "CONFLICT"->409;default->200;};
            String body;
            try {
                if(current.equals("HOLD")){entered.countDown();release.await(3,TimeUnit.SECONDS);}
                if(current.equals("LOST")){commits.incrementAndGet();Thread.sleep(700);}
                String stockState=path.endsWith("confirm")?"SOLD":path.endsWith("release")?"RELEASED":"RESERVED";
                Map<String,Object> data;
                if(path.contains("stock-reservations")) data=Map.of("reservationId",RESERVATION,"orderId",ORDER,"sessionId",2,"ticketTierId",31,"quantity",2,"expiresAt",EXPIRY,"status",stockState);
                else if(path.contains("initializations")) data=Map.of("ticketTierId",31,"sessionId",2,"totalQuantity",10,"availableQuantity",10,"reservedQuantity",0,"soldQuantity",0);
                else if(path.contains("payment-results")) data=Map.of("orderNo",ORDER,"paymentNo",PAYMENT,"accepted",true);
                else if(path.contains("reversals")) data=Map.of("reversalNo","e".repeat(32),"paymentNo",PAYMENT,"orderNo",ORDER,"amount",199,"reason","test","status","SUCCESS","completedAt","2026-01-01T12:00:00","createdAt","2026-01-01T12:00:00");
                else data=Map.of("paymentNo",PAYMENT,"orderNo",ORDER,"userId",1,"amount",199,"status","CREATED","expiresAt",EXPIRY,"notifyStatus","NONE","createdAt","2026-01-01T12:00:00");
                body=current.equals("BAD")?"{broken":status==200?json.writeValueAsString(Map.of("code","OK","data",data))
                    :json.writeValueAsString(Map.of("code",status==409?"CONFLICT":status==404?"RESOURCE_NOT_FOUND":"UPSTREAM_UNAVAILABLE"));
                exchange.getResponseHeaders().set("Content-Type","application/json");
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception ignored){/* 客户端超时导致断开属于响应丢失测试。 */}finally{exchange.close();}
        });stub.start();
        var factory=new JdkClientHttpRequestFactory();factory.setReadTimeout(Duration.ofMillis(400));
        // 只把测试服务名路由到随机端口，客户端请求和响应解析仍使用真实HTTP。
        var builder=RestClient.builder().requestFactory((uri,method)->factory.createRequest(
                URI.create("http://127.0.0.1:"+stub.getAddress().getPort()+uri.getRawPath()),method));
        var policy=new HttpResilienceProperties.Policy(true,2,2,50,Duration.ofMillis(250),2,Duration.ofMillis(500),2);
        var inventory=new InventoryClient(builder,new InventoryClientProperties(null,null,null),
            new OrderInventoryCredential(new OrderInventoryCredentialProperties(TOKEN,null)),policy);
        var payment=new PaymentClient(builder,new PaymentClientProperties(null,null,null),
            new OrderPaymentCredential(new OrderPaymentCredentialProperties(TOKEN,null)),policy);
        var init=new InventoryInitializationClient(builder,new EventPreparationProperties(null,null,null),
            new EventInventoryCredential(new EventInventoryCredentialProperties(TOKEN,null)),policy);
        var notify=new OrderClient(builder,new PaymentNotificationProperties(null,null,null,null,null,null,null,null),
            new PaymentOrderCredential(new PaymentOrderCredentialProperties(TOKEN,null)),policy);
        var reserve=new StockReservationRequest(ORDER,2L,31L,2,LocalDateTime.parse(EXPIRY));
        var create=new PaymentCreateRequest(ORDER,1L,new BigDecimal("199.00"),LocalDateTime.parse(EXPIRY));
        var reverse=new PaymentReversalRequest(PAYMENT,"test");
        List<Operation> operations=List.of(
            new Operation("inventory.reserve",()->inventory.reserve(reserve),inventory.protection()),
            new Operation("inventory.query",()->inventory.getReservation(RESERVATION),inventory.protection()),
            new Operation("inventory.confirm",()->inventory.confirm(RESERVATION),inventory.protection()),
            new Operation("inventory.release",()->inventory.release(RESERVATION),inventory.protection()),
            new Operation("payment.create",()->payment.createPayment(create),payment.protection()),
            new Operation("payment.query",()->payment.getByOrder(ORDER),payment.protection()),
            new Operation("payment.reverse",()->payment.reverse(reverse,ORDER,new BigDecimal("199.00")),payment.protection()),
            new Operation("event.initialize",()->init.initialize(31L,2L,10),init.protection()),
            new Operation("payment.notify",()->notify.notifySuccess(ORDER,PAYMENT),notify.protection()));
        try {
            var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
                "ticket.http-resilience.inventory.window","2","ticket.http-resilience.inventory.minimum-calls","2",
                "ticket.http-resilience.inventory.concurrency","1","ticket.http-resilience.payment.enabled","false",
                "ticket.http-resilience.order.concurrency","4"));
            var configured=new org.springframework.boot.context.properties.bind.Binder(source).bind("ticket.http-resilience",
                org.springframework.boot.context.properties.bind.Bindable.of(HttpResilienceProperties.class)).get();
            check(configured.inventory().concurrency()==1&&configured.inventory().window()==2,"inventory policy binds independently");
            check(!configured.payment().enabled()&&configured.payment().window()==20,"disabled payment keeps default other settings");
            check(configured.order().concurrency()==4,"notification has independent configuration");
            // 提前初始化HTTP转换器，避免把首次类加载时间混入400ms超时测试。
            mode.set("OK");inventory.reserve(reserve);
            for(var operation:operations){
                var guard=operation.protection();var breaker=guard.circuitBreaker();breaker.reset();mode.set("OK");
                int before=calls.get();operation.call().run();check(calls.get()==before+1,operation.name()+" sends exactly one request");
                breaker.reset();mode.set("NOT_FOUND");failure(operation.call());
                check(breaker.getMetrics().getNumberOfBufferedCalls()==0,operation.name()+" ignores 404");
                mode.set("CONFLICT");failure(operation.call());
                check(breaker.getMetrics().getNumberOfBufferedCalls()==0,operation.name()+" ignores business conflict");
                mode.set("BAD");failure(operation.call());
                check(breaker.getMetrics().getNumberOfFailedCalls()==1,operation.name()+" counts corrupt response");
                breaker.reset();mode.set("FAIL");failure(operation.call());failure(operation.call());
                check(breaker.getState()==CircuitBreaker.State.OPEN,operation.name()+" opens on repeated failures");
                before=calls.get();failure(operation.call());check(calls.get()==before,operation.name()+" rejection sends no HTTP");
                mode.set("OK");Thread.sleep(300);operation.call().run();operation.call().run();
                check(breaker.getState()==CircuitBreaker.State.CLOSED,operation.name()+" recovers after successful probes");
                breaker.reset();mode.set("HOLD");entered=new CountDownLatch(2);release=new CountDownLatch(1);
                var one=pool.submit(operation.call());var two=pool.submit(operation.call());
                try {
                    check(entered.await(2,TimeUnit.SECONDS),operation.name()+" occupies two permits");
                    before=calls.get();failure(operation.call());check(calls.get()==before,operation.name()+" bulkhead makes no HTTP");
                    check(breaker.getMetrics().getNumberOfFailedCalls()==0,operation.name()+" local congestion not Event fault");
                }finally{release.countDown();}
                one.get();two.get();check(guard.bulkhead().getMetrics().getAvailableConcurrentCalls()==2,operation.name()+" permits returned");
                System.out.println("PASS "+operation.name());
            }
            // 不同下游完全独立，库存熔断不阻止支付查询。
            inventory.protection().circuitBreaker().transitionToOpenState();payment.protection().circuitBreaker().reset();mode.set("OK");
            payment.getByOrder(ORDER);check(inventory.protection().circuitBreaker().getState()==CircuitBreaker.State.OPEN,"independent downstream circuits");
            // 写请求已提交但响应丢失：客户端抛出异常、只发送一次，原参数仍可用于核对。
            for(var operation:List.of(operations.get(0),operations.get(4),operations.get(6),operations.get(7),operations.get(8))){
                operation.protection().circuitBreaker().reset();mode.set("LOST");int before=calls.get(),saved=commits.get();
                failure(operation.call());check(calls.get()==before+1&&commits.get()==saved+1,operation.name()+" lost response has no automatic retry");
                Thread.sleep(750);mode.set("OK");operation.call().run();check(calls.get()==before+2,operation.name()+" original request can be rechecked");
            }
            check(invalidHeaders.get()==0,"service identity and trace preserved for all calls");
            System.out.println("PASS HTTP resilience: "+checks+" checks; four downstreams, nine operations.");
        }finally{stub.stop(0);pool.shutdownNow();}
    }
    static void failure(Runnable call){try{call.run();throw new AssertionError("expected rejection or remote failure");}catch(RuntimeException expected){checks++;}}
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
