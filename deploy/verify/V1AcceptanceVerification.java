import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import com.byy.ticket.auth.TicketAuthApplication;

/** 第一版验收：六个真实服务、Nacos注册发现、网关及独立MySQL/Redis数据。 */
public class V1AcceptanceVerification extends EventAdministrationVerification {
    final String group="V1_ACCEPT_"+suffix;
    String eventCredential,inventoryCredential;
    Process stockProcess;
    V1AcceptanceVerification(Path root){super(root);}
    public static void main(String[] args)throws Exception{
        var test=new V1AcceptanceVerification(Path.of(args[0]).toAbsolutePath());
        try{test.run();}finally{test.cleanup();}
        System.out.println("PASS V1 acceptance: "+test.checks+" checks; real Nacos and Gateway; owned data and processes cleaned.");
    }
    @Override void run()throws Exception{
        setup("30s");
        int authPort=port(first);
        String[] services={"ticket-auth-service","ticket-event-service","ticket-inventory-service","ticket-order-service","ticket-payment-service","ticket-gateway"};
        int[] ports={authPort,eventPort,inventoryPort,orderPort,paymentPort,gatewayPort};
        for(int i=0;i<services.length;i++){final int j=i;await(()->registered(services[j],ports[j]),"Nacos healthy registration "+services[j]);}
        System.out.println("PASS six services registered; Gateway routes use lb://");
        String adminToken=loginAs(gatewayPort,"admin").path("accessToken").asText();
        status(call(gatewayPort,"POST","/api/auth/register",Map.of("username","buyer","password","Testing_123","nickname","验收用户"),null),200);
        String userToken=loginAs(gatewayPort,"buyer").path("accessToken").asText();
        var created=call(gatewayPort,"POST","/api/admin/events",draft("v1_acceptance"),adminToken);status(created,200);
        long eventId=data(created).path("eventId").asLong(),tier=tier(data(created));
        await(()->preparation(eventId,adminToken).equals("READY"),"Inventory initialized via Nacos");
        status(call(gatewayPort,"POST","/api/admin/events/"+eventId+"/publish",Map.of(),adminToken),200);
        status(call(gatewayPort,"GET","/api/events/"+eventId,null,null),200);
        status(call(gatewayPort,"GET","/api/orders",null,null),401);
        status(call(gatewayPort,"POST","/api/admin/events",draft("forbidden"),userToken),403);
        verifyServiceIdentities(tier,adminToken,userToken);
        String no=createOrder(tier,2,"normal",userToken);
        await(()->orderState(no,userToken).equals("PENDING_PAYMENT"),"stock reserved");
        check(createOrder(tier,2,"normal",userToken).equals(no),"repeated order keeps orderNo");
        check(number("SELECT COUNT(*) FROM "+orderSchema+".t_order")==1,"one order for duplicate key");
        check(number("SELECT reserved_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==2,"duplicate request reserves only once");
        var payment=call(gatewayPort,"POST","/api/orders/"+no+"/payments",Map.of(),userToken);status(payment,200);
        String paymentNo=data(payment).path("paymentNo").asText();
        status(call(gatewayPort,"POST","/api/payments/"+paymentNo+"/simulate-success",Map.of(),userToken),200);
        await(()->orderState(no,userToken).equals("COMPLETED"),"paid order fulfilled");
        var tickets=call(gatewayPort,"GET","/api/orders/"+no+"/tickets",null,userToken);status(tickets,200);
        check(data(tickets).path("tickets").size()==2,"two tickets issued");
        check(number("SELECT sold_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==2,"confirmed sold quantity");
        status(call(gatewayPort,"POST","/api/payments/"+paymentNo+"/simulate-success",Map.of(),userToken),200);
        check(data(call(gatewayPort,"GET","/api/orders/"+no+"/tickets",null,userToken)).path("tickets").size()==2,"duplicate success does not issue extra tickets");
        System.out.println("PASS Gateway registration, login, administration, purchase, payment and tickets");
        status(call(gatewayPort,"POST","/api/auth/register",Map.of("username","other","password","Testing_123","nickname","超时用户"),null),200);
        String other=loginAs(gatewayPort,"other").path("accessToken").asText();
        status(call(gatewayPort,"GET","/api/orders/"+no,null,other),404);
        String expired=createOrder(tier,1,"expiry",other);
        await(()->orderState(expired,other).equals("CLOSED"),"unpaid order closes after real payment window");
        check(number("SELECT available_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==98,"timeout returns stock");
        check(number("SELECT reserved_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==0,"timeout clears reservation");
        System.out.println("PASS real expiry releases stock and user ownership enforced");
        stockProcess.destroy();if(!stockProcess.waitFor(5,TimeUnit.SECONDS))stockProcess.destroyForcibly();
        String pending=createOrder(tier,1,"outage",other);
        check(orderState(pending,other).equals("STOCK_PENDING"),"Inventory outage leaves durable pending order");
        launch("ticket-inventory-service",inventoryPort,inventorySchema,List.of());
        await(()->orderState(pending,other).equals("PENDING_PAYMENT"),"Inventory restart recovers original order");
        check(createOrder(tier,1,"outage",other).equals(pending),"retry after outage retains original order");
        check(number("SELECT reserved_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==1,"recovery only reserves once");
        status(call(gatewayPort,"POST","/api/auth/logout",Map.of(),other),200);
        status(call(gatewayPort,"GET","/api/orders/"+pending,null,other),401);
        System.out.println("PASS Inventory outage and restart recovery; logout invalidates token");
    }
    /** 共用隔离环境启动；压测使用独立目录和更长付款窗口。 */
    List<String> orderStartupArguments(String window){return List.of("--ticket.order.payment-window="+window,"--ticket.order.recovery-delay-ms=100","--ticket.order.retry-base=200ms","--ticket.order.retry-max=1s");}
    String workName(){return "v1-acceptance";}
    void setup(String paymentWindow)throws Exception{
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",System.getenv("LOCAL_MYSQL_USERNAME"),System.getenv("LOCAL_MYSQL_PASSWORD"));
        execute("CREATE DATABASE "+schema);created=true;
        directory=root.resolve(".local/"+workName()+"/"+suffix);Files.createDirectories(directory);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
        Files.writeString(directory.resolve("private.pem"),pem("PRIVATE KEY",keys.getPrivate().getEncoded()));
        Files.writeString(directory.resolve("public.pem"),pem("PUBLIC KEY",keys.getPublic().getEncoded()));
        credential=secret();credentialFile=directory.resolve("payment-order.token");Files.writeString(credentialFile,credential);
        orderCredential=secret();orderCredentialFile=directory.resolve("order-payment.token");Files.writeString(orderCredentialFile,orderCredential);
        initCredential=secret();initCredentialFile=directory.resolve("event-inventory.token");Files.writeString(initCredentialFile,initCredential);
        eventCredential=secret();Files.writeString(directory.resolve("order-event.token"),eventCredential);
        inventoryCredential=secret();Files.writeString(directory.resolve("order-inventory.token"),inventoryCredential);
        first=start(directory);int authPort=port(first);
        eventSchema="ticket_event_auth_verify_"+suffix;inventorySchema="ticket_stock_auth_verify_"+suffix;
        orderSchema="ticket_order_auth_verify_"+suffix;paymentSchema="ticket_payment_auth_verify_"+suffix;
        for(String db:List.of(eventSchema,inventorySchema,orderSchema,paymentSchema)){execute("CREATE DATABASE "+db);schemas.add(db);}
        inventoryPort=freePort();launch("ticket-inventory-service",inventoryPort,inventorySchema,List.of());stockProcess=children.get(children.size()-1);
        eventPort=freePort();launch("ticket-event-service",eventPort,eventSchema,List.of("--ticket.event-preparation.fixed-delay=100"));
        orderPort=freePort();paymentPort=freePort();
        launch("ticket-order-service",orderPort,orderSchema,orderStartupArguments(paymentWindow));
        launch("ticket-payment-service",paymentPort,paymentSchema,List.of("--ticket.payment.simulation-enabled=true","--ticket.payment.notification.fixed-delay=100"));
        gateway(authPort);
    }
    String createOrder(long tier,int quantity,String key,String token)throws Exception{
        var response=call(gatewayPort,"POST","/api/orders",Map.of("ticketTierId",tier,"quantity",quantity,"idempotencyKey",key),token);
        check(response.statusCode()==200||response.statusCode()==202,"order accepted");
        String no=data(response).path("orderNo").asText();check(no.matches("[0-9a-f]{32}"),"valid order number");return no;
    }
    String orderState(String no,String token)throws Exception{return data(call(gatewayPort,"GET","/api/orders/"+no,null,token)).path("status").asText();}
    @Override String outboundCredential(String service){return service.equals("EVENT")?eventCredential:inventoryCredential;}
    @Override void await(Condition condition,String message)throws Exception{
        for(int i=0;i<450;i++){if(condition.test()){check(true,message);return;}Thread.sleep(200);}throw new AssertionError(message);
    }
    List<String> discovery(){return List.of("--spring.cloud.nacos.discovery.enabled=true","--spring.cloud.discovery.enabled=true","--spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848","--spring.cloud.nacos.discovery.group="+group,"--spring.cloud.nacos.discovery.ip=127.0.0.1");}
    @Override ConfigurableApplicationContext start(Path dir,String... extra){
        var args=new ArrayList<>(discovery());args.addAll(List.of("--server.port=0","--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/"+schema+"?characterEncoding=UTF-8",
                "--ticket.auth.private-key-path="+dir.resolve("private.pem"),"--ticket.auth.public-key-path="+dir.resolve("public.pem"),"--ticket.auth.redis-prefix="+prefix,
                "--AUTH_BOOTSTRAP_ADMIN_USERNAME=admin","--AUTH_BOOTSTRAP_ADMIN_PASSWORD=Testing_123","--logging.level.root=ERROR"));args.addAll(List.of(extra));
        return new SpringApplicationBuilder(TicketAuthApplication.class).run(args.toArray(String[]::new));
    }
    @Override void launch(String module,int port,String database,List<String> extra)throws Exception{
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xms128m","-Xmx512m","-jar",root.resolve(module+"/target/"+module+"-1.0-SNAPSHOT.jar").toString(),"--server.port="+port,
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/"+database+"?connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true",
                "--ticket.security.jwk-set-uri=http://127.0.0.1:"+port(first)+"/.well-known/jwks.json","--ticket.security.redis-prefix="+prefix,
                "--ticket.service-auth.payment-order-token=","--ticket.service-auth.credential-path="+credentialFile,
                "--ticket.payment-service-auth.order-payment-token=","--ticket.payment-service-auth.credential-path="+orderCredentialFile,
                "--ticket.inventory-init-auth.token=","--ticket.inventory-init-auth.credential-path="+initCredentialFile,
                "--ticket.event-service-auth.order-event-token=","--ticket.event-service-auth.credential-path="+directory.resolve("order-event.token"),
                "--ticket.inventory-service-auth.order-inventory-token=","--ticket.inventory-service-auth.credential-path="+directory.resolve("order-inventory.token"),"--logging.level.root=ERROR"));
        args.addAll(discovery());args.addAll(extra);
        var process=new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve(module+"-"+port+".log").toFile()).start();children.add(process);
        String ping=module.contains("inventory")?"/internal/stocks/999999":module.contains("payment")?"/api/payments/ping":module.contains("event")?"/api/events/ping":"/api/orders/ping";
        await(()->{if(!process.isAlive())throw new IllegalStateException(module+" exited; inspect owned logs");try{return headers(port,"GET",ping,null,module.contains("inventory")?Map.of("X-Order-Inventory-Credential",inventoryCredential):Map.of()).statusCode()==(module.contains("inventory")?404:200);}catch(java.io.IOException waiting){return false;}},module+" startup");
    }
    @Override void gateway(int authPort)throws Exception{
        gatewayPort=freePort();var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xms128m","-Xmx512m","-jar",root.resolve("ticket-gateway/target/ticket-gateway-1.0-SNAPSHOT.jar").toString(),"--server.port="+gatewayPort,
                "--ticket.security.jwk-set-uri=http://127.0.0.1:"+authPort+"/.well-known/jwks.json","--ticket.security.redis-prefix="+prefix,"--logging.level.root=ERROR"));args.addAll(discovery());
        var process=new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("gateway.log").toFile()).start();children.add(process);
        await(()->{if(!process.isAlive())throw new IllegalStateException("Gateway exited; inspect owned logs");try{return call(gatewayPort,"GET","/api/auth/ping",null,null).statusCode()==200;}catch(java.io.IOException waiting){return false;}},"Gateway Nacos route startup");
    }
    boolean registered(String service,int port)throws Exception{
        var response=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName="+service+"&groupName="+group+"&healthyOnly=true")).timeout(java.time.Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)return false;
        for(var host:JSON.readTree(response.body()).path("hosts"))if(host.path("port").asInt()==port&&host.path("healthy").asBoolean())return true;
        return false;
    }
}
