import com.sun.net.httpserver.*;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 真实Auth签发、订单身份与库存/支付通知闭环；活动仅为HTTP规则桩，所有库和会话隔离。 */
public class OrderAuthVerification extends AuthVerification {
    final List<Process> children=new ArrayList<>();
    final List<String> schemas=new ArrayList<>();
    HttpServer event;
    Path directory, credentialFile;
    String credential;
    int orderPort,inventoryPort,paymentPort;
    String orderSchema,inventorySchema,paymentSchema;
    OrderAuthVerification(Path root){super(root);}
    public static void main(String[] args)throws Exception{
        var test=new OrderAuthVerification(Path.of(args[0]).toAbsolutePath());
        try{test.run();}finally{test.cleanup();}
        System.out.println("PASS order auth: "+test.checks+" checks; owned processes, test schemas and sessions cleaned.");
    }
    @Override
    void run()throws Exception{
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",System.getenv("LOCAL_MYSQL_USERNAME"),System.getenv("LOCAL_MYSQL_PASSWORD"));
        try(var sql=admin.createStatement()){sql.execute("CREATE DATABASE "+schema);created=true;}
        directory=root.resolve(".local/order-auth-verification/"+suffix);Files.createDirectories(directory);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
        Files.writeString(directory.resolve("private.pem"),pem("PRIVATE KEY",keys.getPrivate().getEncoded()));
        Files.writeString(directory.resolve("public.pem"),pem("PUBLIC KEY",keys.getPublic().getEncoded()));
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);credential=HexFormat.of().formatHex(bytes);
        credentialFile=directory.resolve("payment-order.token");Files.writeString(credentialFile,credential);
        first=start(directory);int authPort=port(first);
        status(call(authPort,"POST","/api/auth/register",Map.of("username","buyer","password","Testing_123","nickname","用户1"),null),200);
        status(call(authPort,"POST","/api/auth/register",Map.of("username","other","password","Testing_123","nickname","用户2"),null),200);
        var tokens=login(authPort);String access=tokens.path("accessToken").asText();
        var other=call(authPort,"POST","/api/auth/login",Map.of("username","other","password","Testing_123"),null);status(other,200);
        String otherAccess=JSON.readTree(other.body()).path("data").path("accessToken").asText();
        orderSchema="ticket_order_auth_verify_"+suffix;inventorySchema="ticket_stock_auth_verify_"+suffix;paymentSchema="ticket_payment_auth_verify_"+suffix;
        try(var sql=admin.createStatement()){for(String name:List.of(orderSchema,inventorySchema,paymentSchema)){sql.execute("CREATE DATABASE "+name);schemas.add(name);}}
        event=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);event.setExecutor(pool);
        event.createContext("/",exchange->{
            var now=LocalDateTime.now(ZoneOffset.ofHours(8));
            byte[] body=JSON.writeValueAsBytes(Map.of("code","OK","message","success","data",Map.of(
                    "eventId",1,"sessionId",2,"ticketTierId",3,"ticketTierName","普通票","price",199,
                    "saleStartTime",now.minusDays(1).toString(),"saleEndTime",now.plusDays(1).toString(),"purchaseLimit",2)));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });event.start();
        inventoryPort=freePort();launch("ticket-inventory-service",inventoryPort,inventorySchema,List.of());
        try(var sql=admin.createStatement()){sql.executeUpdate("INSERT INTO "+inventorySchema+".t_ticket_stock(ticket_tier_id,session_id,total_quantity,available_quantity) VALUES(3,2,20,20)");}
        orderPort=freePort();paymentPort=freePort();
        launch("ticket-payment-service",paymentPort,paymentSchema,List.of("--ticket.payment.simulation-enabled=true","--ticket.payment.dev-identity-enabled=true",
                "--ticket.payment.notification.fixed-delay=100","--ticket.payment.notification.retry-base=100ms",
                "--spring.cloud.discovery.client.simple.instances.ticket-order-service[0].uri=http://127.0.0.1:"+orderPort));
        launch("ticket-order-service",orderPort,orderSchema,orderArguments(authPort,true));
        status(call(orderPort,"GET","/api/orders/"+"a".repeat(32),null,null),401);
        status(headers(orderPort,"GET","/api/orders/"+"a".repeat(32),null,Map.of("X-Dev-User-Id","1","X-User-Id","1")),401);
        var input=Map.of("ticketTierId",3,"quantity",2,"idempotencyKey","auth_purchase");
        var create=headers(orderPort,"POST","/api/orders",input,Map.of("Authorization","Bearer "+access,"X-Dev-User-Id","999","X-User-Id","999"));status(create,200);
        String no=JSON.readTree(create.body()).path("data").path("orderNo").asText();check(no.matches("[0-9a-f]{32}"),"real order created");
        try(var sql=admin.createStatement();var result=sql.executeQuery("SELECT user_id FROM "+orderSchema+".t_order WHERE order_no='"+no+"'")){
            result.next();check(result.getLong(1)==1,"Principal userId wins over spoofed headers");
        }
        status(call(orderPort,"GET","/api/orders/"+no,null,access),200);
        status(call(orderPort,"GET","/api/orders/"+no,null,otherAccess),404);
        status(call(orderPort,"GET","/api/orders/"+no+"/tickets",null,otherAccess),404);
        var retry=call(orderPort,"POST","/api/orders",input,access);status(retry,200);check(JSON.readTree(retry.body()).path("data").path("orderNo").asText().equals(no),"idempotent same user order");
        status(call(orderPort,"GET","/api/orders/"+no,null,tokens.path("refreshToken").asText()),401);
        status(call(orderPort,"GET","/api/orders/"+no,null,access.substring(0,access.lastIndexOf('.')+1)+"AAAA"),401);
        var claims=JWTParser.parse(access).getJWTClaimsSet();
        status(call(orderPort,"GET","/api/orders/"+no,null,sign(new JWTClaimsSet.Builder(claims).issuer("wrong").build())),401);
        status(call(orderPort,"GET","/api/orders/"+no,null,sign(new JWTClaimsSet.Builder(claims).audience("wrong").build())),401);
        status(call(orderPort,"GET","/api/orders/"+no,null,sign(new JWTClaimsSet.Builder(claims).claim("sid","invalid").build())),401);
        status(call(orderPort,"GET","/api/orders/"+no,null,sign(new JWTClaimsSet.Builder(claims)
                .issueTime(java.util.Date.from(Instant.now().minusSeconds(120))).expirationTime(java.util.Date.from(Instant.now().minusSeconds(1))).build())),401);
        status(call(orderPort,"POST","/internal/orders/payment-results",Map.of("orderNo",no,"paymentNo","b".repeat(32)),access),401);
        status(headers(orderPort,"POST","/internal/orders/payment-results",Map.of("orderNo",no,"paymentNo","b".repeat(32)),Map.of("X-Payment-Order-Credential","0".repeat(64))),401);
        status(headers(orderPort,"GET","/api/orders/"+no,null,Map.of("X-Payment-Order-Credential",credential)),401);
        status(headers(orderPort,"POST","/internal/orders/stock-reservations",Map.of(),Map.of("X-Payment-Order-Credential",credential)),403);
        var payment=call(orderPort,"POST","/api/orders/"+no+"/payments",Map.of(),access);status(payment,200);
        String paymentNo=JSON.readTree(payment.body()).path("data").path("paymentNo").asText();check(paymentNo.matches("[0-9a-f]{32}"),"real payment created");
        status(headers(paymentPort,"POST","/api/payments/"+paymentNo+"/simulate-success",Map.of(),Map.of("X-Dev-User-Id","1")),200);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean completed=false;
        while(System.nanoTime()<deadline){
            var response=call(orderPort,"GET","/api/orders/"+no,null,access);
            if(JSON.readTree(response.body()).path("data").path("status").asText().equals("COMPLETED")){completed=true;break;}Thread.sleep(100);
        }
        check(completed,"authenticated payment service notice and fulfillment completed");
        var tickets=call(orderPort,"GET","/api/orders/"+no+"/tickets",null,access);status(tickets,200);
        check(JSON.readTree(tickets.body()).path("data").path("tickets").size()==2,"two owned electronic tickets");
        status(headers(orderPort,"POST","/internal/orders/payment-results",Map.of("orderNo",no,"paymentNo",paymentNo),Map.of("X-Payment-Order-Credential",credential)),200);
        try(var sql=admin.createStatement();var result=sql.executeQuery("SELECT notify_status FROM "+paymentSchema+".t_payment WHERE payment_no='"+paymentNo+"'")){
            result.next();check(result.getString(1).equals("DELIVERED"),"notification retained reliable acknowledgement");
        }
        status(call(authPort,"POST","/api/auth/logout",Map.of(),access),200);
        status(call(orderPort,"GET","/api/orders/"+no,null,access),401);
        var renewed=login(authPort);String valid=renewed.path("accessToken").asText();
        int down=freePort();var args=new ArrayList<>(orderArguments(authPort,false));args.add("--spring.data.redis.port="+freePort());args.add("--spring.data.redis.connect-timeout=200ms");args.add("--spring.data.redis.timeout=200ms");
        launch("ticket-order-service",down,orderSchema,args);
        var unavailable=call(down,"GET","/api/orders/"+no,null,valid);status(unavailable,503);
        check(JSON.readTree(unavailable.body()).path("code").asText().equals("SERVICE_BUSY"),"Redis outage denied without changing user identity");
    }
    List<String> orderArguments(int authPort,boolean recovery){return List.of(
            "--ticket.security.jwk-set-uri=http://127.0.0.1:"+authPort+"/.well-known/jwks.json","--ticket.security.redis-prefix="+prefix,
            "--ticket.order.dev-identity-enabled=false","--ticket.order.recovery-enabled="+recovery,"--ticket.order.recovery-delay-ms=100",
            "--spring.cloud.discovery.client.simple.instances.ticket-event-service[0].uri=http://127.0.0.1:"+event.getAddress().getPort(),
            "--spring.cloud.discovery.client.simple.instances.ticket-inventory-service[0].uri=http://127.0.0.1:"+inventoryPort,
            "--spring.cloud.discovery.client.simple.instances.ticket-payment-service[0].uri=http://127.0.0.1:"+paymentPort);}
    /** 所有子进程传入同一随机通知凭证文件，不输出文件内容。 */
    void launch(String module,int port,String database,List<String>extra)throws Exception{
        var arguments=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-jar",root.resolve(module+"/target/"+module+"-1.0-SNAPSHOT.jar").toString(),
                "--server.port="+port,"--spring.cloud.nacos.discovery.enabled=false",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/"+database+"?connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true",
                "--ticket.service-auth.payment-order-token=","--ticket.service-auth.credential-path="+credentialFile,"--logging.level.root=ERROR"));arguments.addAll(extra);
        var process=new ProcessBuilder(arguments).directory(root.toFile()).redirectOutput(directory.resolve(module+"-"+port+".out.log").toFile())
                .redirectError(directory.resolve(module+"-"+port+".err.log").toFile()).start();children.add(process);
        String ping=module.contains("inventory")?"/internal/stocks/3":module.contains("payment")?"/api/payments/ping":"/api/orders/ping";
        int expected=module.contains("inventory")?404:200;
        for(int i=0;i<160;i++){if(!process.isAlive())throw new IllegalStateException(module+" exited; inspect isolated logs");try{if(call(port,"GET",ping,null,null).statusCode()==expected)return;}catch(Exception ignored){}Thread.sleep(200);}
        throw new IllegalStateException(module+" startup timed out; inspect isolated logs");
    }
    HttpResponse<String> headers(int port,String method,String path,Object body,Map<String,String>headers)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));headers.forEach(builder::header);
        if(body!=null)builder.header("Content-Type","application/json");
        return HTTP.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    int freePort()throws Exception{try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}}
    @Override
    void cleanup()throws Exception{
        for(var process:children){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}
        if(event!=null)event.stop(0);
        try{if(admin!=null)try(var sql=admin.createStatement()){for(String database:schemas)if(database.matches("ticket_(order|stock|payment)_auth_verify_[0-9a-f]{32}"))sql.execute("DROP DATABASE "+database);}}
        finally{super.cleanup();}
    }
}
