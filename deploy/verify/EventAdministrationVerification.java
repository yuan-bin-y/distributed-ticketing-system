import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.JsonNode;
import com.nimbusds.jwt.JWTParser;

/** 六个真实服务、隔离MySQL/Redis验证活动准备与购票；代理只注入初始化网络故障。 */
public class EventAdministrationVerification extends OrderAuthVerification {
    String eventSchema,initCredential;Path initCredentialFile;int eventPort,proxyPort,gatewayPort;
    HttpServer proxy;Process eventProcess;
    final AtomicReference<String> mode=new AtomicReference<>("PASS");
    volatile long blockedTier;
    EventAdministrationVerification(Path root){super(root);}
    public static void main(String[] args)throws Exception{
        var test=new EventAdministrationVerification(Path.of(args[0]).toAbsolutePath());
        try{test.run();}finally{test.cleanup();}
        System.out.println("PASS event administration: "+test.checks+" checks; owned databases, sessions and processes cleaned.");
    }
    @Override void run()throws Exception{
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",System.getenv("LOCAL_MYSQL_USERNAME"),System.getenv("LOCAL_MYSQL_PASSWORD"));
        execute("CREATE DATABASE "+schema);created=true;
        directory=root.resolve(".local/event-administration-verification/"+suffix);Files.createDirectories(directory);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
        Files.writeString(directory.resolve("private.pem"),pem("PRIVATE KEY",keys.getPrivate().getEncoded()));Files.writeString(directory.resolve("public.pem"),pem("PUBLIC KEY",keys.getPublic().getEncoded()));
        credential=secret();credentialFile=directory.resolve("payment-order.token");Files.writeString(credentialFile,credential);
        orderCredential=secret();orderCredentialFile=directory.resolve("order-payment.token");Files.writeString(orderCredentialFile,orderCredential);
        initCredential=secret();initCredentialFile=directory.resolve("event-inventory.token");Files.writeString(initCredentialFile,initCredential);
        first=start(directory,"--AUTH_BOOTSTRAP_ADMIN_USERNAME=admin","--AUTH_BOOTSTRAP_ADMIN_PASSWORD=Testing_123");
        int authPort=port(first);
        var administrator=loginAs(authPort,"admin");String adminToken=administrator.path("accessToken").asText();
        check(JWTParser.parse(adminToken).getJWTClaimsSet().getStringClaim("role").equals("ADMIN"),"bootstrap administrator role signed");
        status(call(authPort,"POST","/api/auth/register",Map.of("username","buyer","password","Testing_123","nickname","用户","role","ADMIN"),null),200);
        var buyerTokens=loginAs(authPort,"buyer");String userToken=buyerTokens.path("accessToken").asText();
        check(JWTParser.parse(userToken).getJWTClaimsSet().getStringClaim("role").equals("USER"),"public registration cannot elevate role");
        var refreshed=call(authPort,"POST","/api/auth/refresh",Map.of("refreshToken",administrator.path("refreshToken").asText()),null);status(refreshed,200);
        check(JWTParser.parse(JSON.readTree(refreshed.body()).path("data").path("accessToken").asText()).getJWTClaimsSet().getStringClaim("role").equals("ADMIN"),"refresh reloads database role");
        eventSchema="ticket_event_auth_verify_"+suffix;inventorySchema="ticket_stock_auth_verify_"+suffix;orderSchema="ticket_order_auth_verify_"+suffix;paymentSchema="ticket_payment_auth_verify_"+suffix;
        for(String database:List.of(eventSchema,inventorySchema,orderSchema,paymentSchema)){execute("CREATE DATABASE "+database);schemas.add(database);}
        inventoryPort=freePort();launch("ticket-inventory-service",inventoryPort,inventorySchema,List.of());
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);proxy.setExecutor(eventWorkers);proxy.createContext("/",this::forward);proxy.start();proxyPort=proxy.getAddress().getPort();
        eventPort=freePort();launch("ticket-event-service",eventPort,eventSchema,eventArguments(authPort,false));eventProcess=children.get(children.size()-1);
        var draft=draft("first_draft");
        status(call(eventPort,"POST","/api/admin/events",draft,null),401);
        status(call(eventPort,"POST","/api/admin/events",draft,userToken),403);
        status(headers(eventPort,"POST","/api/admin/events",draft,Map.of("X-User-Role","ADMIN","X-Dev-User-Id","1")),401);
        var created=call(eventPort,"POST","/api/admin/events",draft,adminToken);status(created,200);var data=data(created);long id=data.path("eventId").asLong();long tier=tier(data),session=session(data);
        check(data.path("status").asText().equals("DRAFT"),"new event saved as draft");
        status(call(eventPort,"GET","/api/events/"+id,null,null),404);
        status(headers(eventPort,"GET","/internal/ticket-tiers/"+tier+"/purchase-rule",null,Map.of("X-Order-Event-Credential",outboundCredential("EVENT"))),404);
        status(call(eventPort,"POST","/api/admin/events/"+id+"/publish",Map.of(),adminToken),409);
        var again=call(eventPort,"POST","/api/admin/events",draft,adminToken);status(again,200);check(data(again).path("eventId").asLong()==id,"create retry preserves identifiers");
        var changed=new HashMap<>(draft);changed.put("name","changed");status(call(eventPort,"POST","/api/admin/events",changed,adminToken),409);
        status(call(inventoryPort,"POST","/internal/stocks/initializations",Map.of("ticketTierId",tier,"sessionId",session,"totalQuantity",100),adminToken),401);
        mode.set("LOST");status(call(eventPort,"POST","/api/admin/events/"+id+"/prepare",Map.of(),adminToken),200);
        check(preparation(id,adminToken).equals("PENDING"),"committed response loss retains pending");
        check(number("SELECT total_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==100,"Inventory commit exists despite timeout");
        status(call(eventPort,"POST","/api/admin/events/"+id+"/publish",Map.of(),adminToken),409);
        // 重启+两个恢复实例，使用原ID和数量核对，READY保存失败后也可恢复。
        mode.set("PASS");eventProcess.destroy();eventProcess.waitFor(5,TimeUnit.SECONDS);
        launch("ticket-event-service",eventPort,eventSchema,eventArguments(authPort,true));
        int secondEvent=freePort();launch("ticket-event-service",secondEvent,eventSchema,eventArguments(authPort,true));
        await(()->preparation(id,adminToken).equals("READY"),"restart workers recovered same initialization");
        check(number("SELECT COUNT(*) FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==1,"repeat initialization only one stock record");
        status(call(eventPort,"POST","/api/admin/events/"+id+"/publish",Map.of(),adminToken),200);
        status(call(eventPort,"POST","/api/admin/events/"+id+"/publish",Map.of(),adminToken),200);
        status(call(eventPort,"GET","/api/events/"+id,null,null),200);
        status(headers(eventPort,"GET","/internal/ticket-tiers/"+tier+"/purchase-rule",null,Map.of("X-Order-Event-Credential",outboundCredential("EVENT"))),200);
        verifyServiceIdentities(tier,adminToken,userToken);
        mode.set("BAD_FACT");var wrong=createDraft("bad_fact",adminToken);long wrongId=wrong.path("eventId").asLong();
        await(()->preparation(wrongId,adminToken).equals("REVIEW_REQUIRED"),"mismatched success fact stops publication");
        status(call(eventPort,"POST","/api/admin/events/"+wrongId+"/publish",Map.of(),adminToken),409);
        mode.set("PASS");status(call(eventPort,"POST","/api/admin/events/"+wrongId+"/prepare",Map.of(),adminToken),200);
        await(()->preparation(wrongId,adminToken).equals("READY"),"administrator retries original parameters after diagnosis");
        execute("CREATE TRIGGER "+eventSchema+".publish_fault BEFORE UPDATE ON "+eventSchema+".t_event FOR EACH ROW BEGIN IF NEW.status='PUBLISHED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='publish fault'; END IF; END");
        status(call(eventPort,"POST","/api/admin/events/"+wrongId+"/publish",Map.of(),adminToken),500);
        check(number("SELECT COUNT(*) FROM "+eventSchema+".t_event_session WHERE event_id="+wrongId+" AND status='PUBLISHED'")==0,"publication failure rolls back session status");
        execute("DROP TRIGGER "+eventSchema+".publish_fault");
        mode.set("UNAVAILABLE");
        var bulk=new HashMap<String,Object>(draft("two_tiers"));
        var bulkSession=new HashMap<String,Object>((Map<String,Object>)((List<?>)bulk.get("sessions")).get(0));
        bulkSession.put("ticketTiers",List.of(Map.of("name","普通票","price",199,"totalQuantity",100),Map.of("name","VIP","price",399,"totalQuantity",50)));
        bulk.put("sessions",List.of(bulkSession));
        var bulkResponse=call(eventPort,"POST","/api/admin/events",bulk,adminToken);status(bulkResponse,200);
        var bulkData=data(bulkResponse);long bulkId=bulkData.path("eventId").asLong();blockedTier=bulkData.path("sessions").get(0).path("ticketTiers").get(1).path("ticketTierId").asLong();
        mode.set("SELECTIVE");await(()->preparation(bulkId,adminToken).equals("READY"),"first bulk tier prepared");
        status(call(eventPort,"POST","/api/admin/events/"+bulkId+"/publish",Map.of(),adminToken),409);
        mode.set("PASS");await(()->number("SELECT COUNT(*) FROM "+eventSchema+".t_ticket_tier WHERE id="+blockedTier+" AND preparation_status='READY'")==1,"second bulk tier recovers");
        status(call(eventPort,"POST","/api/admin/events/"+bulkId+"/publish",Map.of(),adminToken),200);
        mode.set("UNAVAILABLE");var retry=createDraft("retry_failure",adminToken);long retryId=retry.path("eventId").asLong();
        await(()->number("SELECT preparation_attempts FROM "+eventSchema+".t_ticket_tier WHERE id="+tier(retry))>0,"unavailable initialization attempted");
        check(preparation(retryId,adminToken).equals("PENDING"),"503 keeps recoverable progress");mode.set("PASS");
        await(()->preparation(retryId,adminToken).equals("READY"),"outage recovers without replacing identifiers");
        // 数据库结果保存故障：Inventory成功不能让发布检查绕过本地READY。
        execute("CREATE TRIGGER "+eventSchema+".preparation_fault BEFORE UPDATE ON "+eventSchema+".t_ticket_tier FOR EACH ROW BEGIN IF NEW.preparation_status='READY' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='result save fault'; END IF; END");
        var rollback=createDraft("ready_rollback",adminToken);long rollbackId=rollback.path("eventId").asLong();
        await(()->number("SELECT COUNT(*) FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier(rollback))==1,"remote stock saved while local READY fails");
        check(preparation(rollbackId,adminToken).equals("PENDING"),"local save failure does not falsely claim ready");
        status(call(eventPort,"POST","/api/admin/events/"+rollbackId+"/publish",Map.of(),adminToken),409);
        execute("DROP TRIGGER "+eventSchema+".preparation_fault");await(()->preparation(rollbackId,adminToken).equals("READY"),"local result save recovers");
        // 草稿三张表整体回滚，不能留下半个活动。
        execute("CREATE TRIGGER "+eventSchema+".tier_insert_fault BEFORE INSERT ON "+eventSchema+".t_ticket_tier FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='tier insert fault'");
        status(call(eventPort,"POST","/api/admin/events",draft("draft_rollback"),adminToken),500);
        check(number("SELECT COUNT(*) FROM "+eventSchema+".t_event WHERE creation_key='draft_rollback'")==0,"draft insert failure rolls back event and session");
        execute("DROP TRIGGER "+eventSchema+".tier_insert_fault");
        orderPort=freePort();paymentPort=freePort();
        launch("ticket-payment-service",paymentPort,paymentSchema,List.of("--ticket.payment.simulation-enabled=true","--ticket.payment.notification.fixed-delay=100",
                "--ticket.security.jwk-set-uri=http://127.0.0.1:"+authPort+"/.well-known/jwks.json","--ticket.security.redis-prefix="+prefix,
                "--spring.cloud.discovery.client.simple.instances.ticket-order-service[0].uri=http://127.0.0.1:"+orderPort));
        launch("ticket-order-service",orderPort,orderSchema,List.of("--ticket.security.jwk-set-uri=http://127.0.0.1:"+authPort+"/.well-known/jwks.json","--ticket.security.redis-prefix="+prefix,
                "--ticket.order.recovery-delay-ms=100","--spring.cloud.discovery.client.simple.instances.ticket-event-service[0].uri=http://127.0.0.1:"+eventPort,
                "--spring.cloud.discovery.client.simple.instances.ticket-inventory-service[0].uri=http://127.0.0.1:"+inventoryPort,
                "--spring.cloud.discovery.client.simple.instances.ticket-payment-service[0].uri=http://127.0.0.1:"+paymentPort));
        gateway(authPort);status(call(gatewayPort,"GET","/api/admin/events/"+id,null,userToken),403);
        status(call(gatewayPort,"GET","/api/admin/events/"+id,null,adminToken),200);
        status(call(gatewayPort,"GET","/api/events/"+id,null,null),200);
        var order=call(gatewayPort,"POST","/api/orders",Map.of("ticketTierId",tier,"quantity",2,"idempotencyKey","admin_event_purchase"),userToken);
        check(order.statusCode()==200||order.statusCode()==202,"Gateway buys API-created event");String no=data(order).path("orderNo").asText();
        await(()->data(call(orderPort,"GET","/api/orders/"+no,null,userToken)).path("status").asText().equals("PENDING_PAYMENT"),"real reservation ready");
        var payment=call(gatewayPort,"POST","/api/orders/"+no+"/payments",Map.of(),userToken);status(payment,200);String paymentNo=data(payment).path("paymentNo").asText();
        status(call(gatewayPort,"POST","/api/payments/"+paymentNo+"/simulate-success",Map.of(),userToken),200);
        await(()->data(call(gatewayPort,"GET","/api/orders/"+no,null,userToken)).path("status").asText().equals("COMPLETED"),"real six-service purchase completed");
        var tickets=call(gatewayPort,"GET","/api/orders/"+no+"/tickets",null,userToken);status(tickets,200);check(data(tickets).path("tickets").size()==2,"two tickets from managed event");
        var initialize=Map.of("ticketTierId",tier,"sessionId",session,"totalQuantity",100);
        status(init(initialize),200);check(number("SELECT sold_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==2,"reinitialization does not reset sold tickets");
        status(init(Map.of("ticketTierId",tier,"sessionId",session,"totalQuantity",101)),409);
        var initGate=new CountDownLatch(1);var repeated=new ArrayList<Future<HttpResponse<String>>>();
        for(int i=0;i<8;i++)repeated.add(pool.submit(()->{initGate.await();return init(initialize);}));initGate.countDown();
        for(var future:repeated)status(future.get(15,TimeUnit.SECONDS),200);
        check(number("SELECT available_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==98,"concurrent initialization preserves stock balance");
        status(call(gatewayPort,"GET","/internal/stocks/initializations/"+tier,null,adminToken),403);
        status(call(authPort,"POST","/api/auth/logout",Map.of(),adminToken),200);
        status(call(eventPort,"GET","/api/admin/events/"+id,null,adminToken),401);status(call(gatewayPort,"GET","/api/admin/events/"+id,null,adminToken),401);
    }
    String secret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    @Override void launch(String module,int port,String database,List<String> extra)throws Exception{
        var args=new ArrayList<>(extra);
        if(module.contains("event")){
            if(extra.stream().noneMatch(v->v.startsWith("--ticket.event-cache.prefix=")))
                args.add("--ticket.event-cache.prefix=ticket:{cache_verify_"+suffix+"}:");
            if(extra.stream().noneMatch(v->v.startsWith("--ticket.event-cache.enabled=")))
                args.add("--ticket.event-cache.enabled=false");
        }
        args.add("--ticket.inventory-init-auth.token=");args.add("--ticket.inventory-init-auth.credential-path="+initCredentialFile);
        super.launch(module,port,database,args);
    }
    List<String> eventArguments(int authPort,boolean recover){return List.of("--ticket.security.jwk-set-uri=http://127.0.0.1:"+authPort+"/.well-known/jwks.json","--ticket.security.redis-prefix="+prefix,
            "--ticket.event-preparation.enabled="+recover,"--ticket.event-preparation.fixed-delay=100","--ticket.event-preparation.read-timeout=300ms",
            "--spring.cloud.discovery.client.simple.instances.ticket-inventory-service[0].uri=http://127.0.0.1:"+proxyPort);}
    Map<String,Object> draft(String key){var now=LocalDateTime.now(ZoneOffset.ofHours(8)).withNano(0);return Map.of("idempotencyKey",key,"name","管理接口活动","category","CONCERT","sessions",List.of(Map.of("name","首场","venueName","测试场馆","venueAddress","测试地址",
            "startTime",now.plusDays(1).toString(),"endTime",now.plusDays(1).plusHours(2).toString(),"saleStartTime",now.minusHours(1).toString(),"saleEndTime",now.plusHours(2).toString(),"purchaseLimit",2,
            "ticketTiers",List.of(Map.of("name","普通票","price",199,"totalQuantity",100)))));}
    JsonNode createDraft(String key,String token)throws Exception{var response=call(eventPort,"POST","/api/admin/events",draft(key),token);status(response,200);return data(response);}
    JsonNode loginAs(int port,String name)throws Exception{var response=call(port,"POST","/api/auth/login",Map.of("username",name,"password","Testing_123"),null);status(response,200);return data(response);}
    JsonNode data(HttpResponse<String> response){return JSON.readTree(response.body()).path("data");}
    long tier(JsonNode data){return data.path("sessions").get(0).path("ticketTiers").get(0).path("ticketTierId").asLong();}
    long session(JsonNode data){return data.path("sessions").get(0).path("sessionId").asLong();}
    String preparation(long id,String token)throws Exception{return data(call(eventPort,"GET","/api/admin/events/"+id,null,token)).path("sessions").get(0).path("ticketTiers").get(0).path("preparationStatus").asText();}
    /** 验证身份隔离、路径和HTTP方法限制；被拒绝的写请求不能改变库存。 */
    void verifyServiceIdentities(long tier,String adminToken,String userToken)throws Exception{
        String rule="/internal/ticket-tiers/"+tier+"/purchase-rule";
        String stock="/internal/stocks/"+tier;
        var eventHeaders=Map.of("X-Order-Event-Credential",outboundCredential("EVENT"));
        var inventoryHeaders=Map.of("X-Order-Inventory-Credential",outboundCredential("INVENTORY"));
        status(call(eventPort,"GET",rule,null,null),401);
        status(call(eventPort,"GET",rule,null,adminToken),401);
        status(call(eventPort,"GET",rule,null,userToken),401);
        status(headers(eventPort,"GET",rule,null,Map.of("X-Order-Event-Credential",outboundCredential("INVENTORY"))),401);
        status(headers(eventPort,"GET",rule,null,Map.of("X-Order-Event-Credential","wrong")),401);
        status(headers(eventPort,"POST",rule,Map.of(),eventHeaders),403);
        status(headers(eventPort,"GET","/internal/unlisted",null,eventHeaders),403);
        status(headers(eventPort,"GET",rule,null,eventHeaders),200);
        long available=number("SELECT available_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier);
        for(String path:List.of("/internal/stock-reservations","/internal/stock-reservations/no/confirm","/internal/stock-reservations/no/release")){
            status(call(inventoryPort,"POST",path,Map.of(),null),401);
            status(call(inventoryPort,"POST",path,Map.of(),adminToken),401);
            status(headers(inventoryPort,"POST",path,Map.of(),Map.of("X-Event-Inventory-Credential",initCredential)),401);
        }
        status(call(inventoryPort,"GET",stock,null,userToken),401);
        status(headers(inventoryPort,"GET",stock,null,Map.of("X-Order-Inventory-Credential",outboundCredential("EVENT"))),401);
        status(headers(inventoryPort,"GET",stock,null,inventoryHeaders),200);
        status(headers(inventoryPort,"POST",stock,Map.of(),inventoryHeaders),403);
        status(headers(inventoryPort,"GET","/internal/unlisted",null,inventoryHeaders),403);
        status(headers(inventoryPort,"POST","/internal/stocks/initializations",Map.of(),inventoryHeaders),401);
        status(headers(inventoryPort,"GET","/internal/stocks/initializations/"+tier,null,Map.of("X-Event-Inventory-Credential",initCredential)),200);
        check(number("SELECT available_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==available,"denied writes preserve stock");
    }
    HttpResponse<String> init(Object body)throws Exception{return headers(inventoryPort,"POST","/internal/stocks/initializations",body,Map.of("X-Event-Inventory-Credential",initCredential));}
    void execute(String sql)throws Exception{try(var statement=admin.createStatement()){statement.execute(sql);}}
    long number(String sql)throws Exception{try(var statement=admin.createStatement();var result=statement.executeQuery(sql)){return result.next()?result.getLong(1):0;}}
    interface Condition{boolean test()throws Exception;}
    void await(Condition condition,String message)throws Exception{for(int i=0;i<180;i++){if(condition.test()){check(true,message);return;}Thread.sleep(100);}throw new AssertionError(message);}
    void forward(HttpExchange exchange)throws java.io.IOException{
        try{
            String fault=mode.get();
            var input=JSON.readTree(exchange.getRequestBody().readAllBytes());
            if(fault.equals("UNAVAILABLE")||fault.equals("SELECTIVE")&&input.path("ticketTierId").asLong()==blockedTier){send(exchange,503,"{}");return;}
            var response=headers(inventoryPort,"POST","/internal/stocks/initializations",input,
                    Map.of("X-Event-Inventory-Credential",exchange.getRequestHeaders().getFirst("X-Event-Inventory-Credential")));
            if(fault.equals("LOST"))Thread.sleep(1200);
            String body=response.body();
            if(fault.equals("BAD_FACT"))body=body.replace("\"totalQuantity\":100","\"totalQuantity\":101");
            send(exchange,response.statusCode(),body);
        }catch(Exception failure){try{send(exchange,503,"{}");}catch(Exception ignored){}}finally{exchange.close();}
    }
    void send(HttpExchange exchange,int status,String body)throws java.io.IOException{byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
    void gateway(int authPort)throws Exception{
        gatewayPort=freePort();var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-jar",root.resolve("ticket-gateway/target/ticket-gateway-1.0-SNAPSHOT.jar").toString(),
                "--server.port="+gatewayPort,"--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.discovery.enabled=false","--ticket.security.jwk-set-uri=http://127.0.0.1:"+authPort+"/.well-known/jwks.json","--ticket.security.redis-prefix="+prefix,"--logging.level.root=ERROR"));
        String[] names={"events","orders","payments","auth"};int[] ports={eventPort,orderPort,paymentPort,authPort};
        // 此回归验证交易和权限；限流由专门的跨实例压测验证。
        args.add("--ticket.rate-limit.enabled=false");
        for(int i=0;i<4;i++){String key="--spring.cloud.gateway.server.webflux.routes["+i+"]";args.add(key+".id=verify-"+names[i]);args.add(key+".uri=http://127.0.0.1:"+ports[i]);args.add(key+".predicates[0]=Path=/api/"+names[i]+"/**"+(i==0?",/api/admin/events/**":""));}
        var process=new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("gateway.log").toFile()).start();children.add(process);
        await(()->{if(!process.isAlive())throw new IllegalStateException("Gateway exited; inspect owned logs");try{return call(gatewayPort,"GET","/api/auth/ping",null,null).statusCode()==200;}catch(java.io.IOException starting){return false;}},"Gateway startup");
    }
    @Override void cleanup()throws Exception{if(proxy!=null)proxy.stop(0);super.cleanup();}
}
