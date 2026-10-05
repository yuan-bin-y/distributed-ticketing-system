import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.nimbusds.jwt.*;

/** 真实用户认证、支付归属和双向服务凭证验证；继承隔离数据库和进程清理。 */
public class PaymentAuthVerification extends OrderAuthVerification {
    PaymentAuthVerification(Path root){super(root);}
    public static void main(String[] args)throws Exception{
        var test=new PaymentAuthVerification(Path.of(args[0]).toAbsolutePath());
        try{test.run();}finally{test.cleanup();}
        System.out.println("PASS payment auth: "+test.checks+" checks; isolated processes, schemas and sessions cleaned.");
    }
    @Override void run()throws Exception{
        super.run();
        int authPort=port(first);
        var tokens=login(authPort);String access=tokens.path("accessToken").asText();
        var other=call(authPort,"POST","/api/auth/login",Map.of("username","other","password","Testing_123"),null);status(other,200);
        String otherAccess=JSON.readTree(other.body()).path("data").path("accessToken").asText();
        String no,paymentNo;
        try(var sql=admin.createStatement();var result=sql.executeQuery("SELECT order_no,payment_no FROM "+paymentSchema+".t_payment LIMIT 1")){
            result.next();no=result.getString(1);paymentNo=result.getString(2);
        }
        String path="/api/payments/"+paymentNo;
        status(call(paymentPort,"GET",path,null,null),401);
        status(headers(paymentPort,"GET",path,null,Map.of("X-Dev-User-Id","1","X-User-Id","1")),401);
        status(headers(paymentPort,"GET",path,null,Map.of("Authorization","Bearer "+access,"X-Dev-User-Id","2","X-User-Id","2")),200);
        status(call(paymentPort,"GET",path,null,otherAccess),404);
        status(call(paymentPort,"POST",path+"/simulate-success",Map.of(),otherAccess),404);
        status(call(paymentPort,"GET",path,null,tokens.path("refreshToken").asText()),401);
        status(call(paymentPort,"GET",path,null,access.substring(0,access.lastIndexOf('.')+1)+"AAAA"),401);
        var claims=JWTParser.parse(access).getJWTClaimsSet();
        status(call(paymentPort,"GET",path,null,sign(new JWTClaimsSet.Builder(claims).issuer("wrong").build())),401);
        status(call(paymentPort,"GET",path,null,sign(new JWTClaimsSet.Builder(claims).audience("wrong").build())),401);
        status(call(paymentPort,"GET",path,null,sign(new JWTClaimsSet.Builder(claims).claim("sid","invalid").build())),401);
        String internal="/internal/payments/by-order/"+no;
        status(call(paymentPort,"GET",internal,null,null),401);
        status(call(paymentPort,"GET",internal,null,access),401);
        status(headers(paymentPort,"GET",internal,null,Map.of("X-Payment-Order-Credential",credential)),401);
        status(headers(paymentPort,"GET",internal,null,Map.of("X-Order-Payment-Credential","0".repeat(64))),401);
        status(headers(paymentPort,"GET",internal,null,Map.of("X-Order-Payment-Credential",orderCredential)),200);
        status(headers(paymentPort,"DELETE",internal,null,Map.of("X-Order-Payment-Credential",orderCredential)),403);
        status(headers(paymentPort,"GET",path,null,Map.of("X-Order-Payment-Credential",orderCredential)),401);
        status(headers(orderPort,"POST","/internal/orders/payment-results",Map.of("orderNo",no,"paymentNo",paymentNo),Map.of("X-Payment-Order-Credential",orderCredential)),401);
        status(call(authPort,"POST","/api/auth/logout",Map.of(),access),200);
        status(call(paymentPort,"GET",path,null,access),401);
        String valid=login(authPort).path("accessToken").asText();
        int down=freePort();
        launch("ticket-payment-service",down,paymentSchema,List.of("--ticket.payment.notification.enabled=false",
                "--ticket.security.jwk-set-uri=http://127.0.0.1:"+authPort+"/.well-known/jwks.json","--ticket.security.redis-prefix="+prefix,
                "--spring.data.redis.port="+freePort(),"--spring.data.redis.connect-timeout=200ms","--spring.data.redis.timeout=200ms"));
        status(call(down,"GET",path,null,valid),503);
        status(headers(down,"GET",internal,null,Map.of("X-Order-Payment-Credential",orderCredential)),200);
        gateway(authPort,otherAccess);
    }
    /** 固定地址运行真实 Gateway、Auth、Order、Inventory、Payment；活动规则使用本地HTTP桩。 */
    void gateway(int authPort,String otherAccess)throws Exception{
        int port=freePort();
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-jar",
                root.resolve("ticket-gateway/target/ticket-gateway-1.0-SNAPSHOT.jar").toString(),"--server.port="+port,
                "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.discovery.enabled=false",
                "--ticket.security.jwk-set-uri=http://127.0.0.1:"+authPort+"/.well-known/jwks.json","--ticket.security.redis-prefix="+prefix,"--logging.level.root=ERROR"));
        String[] names={"events","orders","payments","auth"};int[] ports={event.getAddress().getPort(),orderPort,paymentPort,authPort};
        for(int i=0;i<names.length;i++){
            String key="--spring.cloud.gateway.server.webflux.routes["+i+"]";
            args.add(key+".id=verify-"+names[i]);args.add(key+".uri=http://127.0.0.1:"+ports[i]);args.add(key+".predicates[0]=Path=/api/"+names[i]+"/**");
        }
        var process=new ProcessBuilder(args).directory(root.toFile()).redirectOutput(directory.resolve("gateway.out.log").toFile())
                .redirectError(directory.resolve("gateway.err.log").toFile()).start();children.add(process);
        boolean ready=false;
        for(int i=0;i<160;i++){if(!process.isAlive())throw new IllegalStateException("Gateway exited; inspect logs");try{if(call(port,"GET","/api/auth/ping",null,null).statusCode()==200){ready=true;break;}}catch(Exception ignored){}Thread.sleep(200);}
        check(ready,"Gateway ready");
        status(call(port,"POST","/api/auth/register",Map.of("username","gatewaybuyer","password","Testing_123","nickname","网关用户"),null),200);
        var gatewayLogin=call(port,"POST","/api/auth/login",Map.of("username","gatewaybuyer","password","Testing_123"),null);status(gatewayLogin,200);
        String access=JSON.readTree(gatewayLogin.body()).path("data").path("accessToken").asText();
        var create=call(port,"POST","/api/orders",Map.of("ticketTierId",3,"quantity",1,"idempotencyKey","gateway_purchase"),access);status(create,200);
        String no=JSON.readTree(create.body()).path("data").path("orderNo").asText();
        var payment=call(port,"POST","/api/orders/"+no+"/payments",Map.of(),access);status(payment,200);
        String paymentNo=JSON.readTree(payment.body()).path("data").path("paymentNo").asText();String path="/api/payments/"+paymentNo;
        status(call(port,"GET",path,null,access),200);
        status(call(port,"POST",path+"/simulate-success",Map.of(),otherAccess),404);
        status(call(port,"POST",path+"/simulate-success",Map.of("amount",0.01),access),200);
        boolean completed=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){var order=call(port,"GET","/api/orders/"+no,null,access);if(JSON.readTree(order.body()).path("data").path("status").asText().equals("COMPLETED")){completed=true;break;}Thread.sleep(100);}
        check(completed,"Gateway purchase completed");
        var tickets=call(port,"GET","/api/orders/"+no+"/tickets",null,access);status(tickets,200);
        check(JSON.readTree(tickets.body()).path("data").path("tickets").size()==1,"Gateway owned electronic ticket");
        status(call(port,"GET",path,null,otherAccess),404);
        status(call(port,"GET","/api/orders/"+no+"/tickets",null,otherAccess),404);
        status(call(port,"GET","/internal/payments/by-order/"+no,null,access),403);
        status(call(port,"POST","/api/auth/logout",Map.of(),access),200);
        status(call(port,"GET",path,null,access),401);
        status(call(paymentPort,"GET",path,null,access),401);
        status(call(orderPort,"GET","/api/orders/"+no,null,access),401);
    }
}
