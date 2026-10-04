import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.node.ObjectNode;

/** 三个真实服务及MySQL验证；已落库订单夹具通过真实库存接口预留，代理注入响应丢失和竞争。 */
public class PaymentFulfillmentVerification extends OrderPaymentVerification {
    final String stockSchema="ticket_stock_pay_verify_"+suffix;
    Connection stockDb;
    HttpServer inventoryProxy;
    int stockPort,inventoryProxyPort;
    Process orderProcess,paymentProcess;
    final AtomicReference<String> inventoryFault=new AtomicReference<>("PASS"),paymentFault=new AtomicReference<>("PASS");
    final AtomicInteger confirms=new AtomicInteger(),reversals=new AtomicInteger();
    volatile Gate gate;
    static class Gate {
        final String reservation;
        final CountDownLatch releaseEntered=new CountDownLatch(1),confirmEntered=new CountDownLatch(1),
                allowRelease=new CountDownLatch(1),allowConfirm=new CountDownLatch(1);
        Gate(String reservation){this.reservation=reservation;}
    }
    PaymentFulfillmentVerification(Path root)throws Exception{super(root);}
    public static void main(String[] args)throws Exception{
        var test=new PaymentFulfillmentVerification(Path.of(args[0]).toAbsolutePath());
        try{test.runFulfillment();}finally{test.cleanup();}
        System.out.println("PASS payment fulfillment: "+test.checks+" checks; isolated databases and owned processes removed.");
    }
    void runFulfillment()throws Exception{
        user=requireEnv("LOCAL_MYSQL_USERNAME");password=requireEnv("LOCAL_MYSQL_PASSWORD");
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",user,password);
        try(var sql=admin.createStatement()){
            for(String schema:List.of(orderSchema,paymentSchema,stockSchema)){sql.execute("CREATE DATABASE "+schema);createdSchemas.add(schema);}
        }
        orderDb=DriverManager.getConnection(dbUrl(orderSchema),user,password);
        paymentDb=DriverManager.getConnection(dbUrl(paymentSchema),user,password);
        stockDb=DriverManager.getConnection(dbUrl(stockSchema),user,password);
        stockPort=freePort();var stock=start("ticket-inventory-service",stockPort,stockSchema,List.of());
        waitPing(stock,stockPort,"/internal/stocks/1",404);
        try(var sql=stockDb.createStatement()){sql.execute("INSERT INTO t_ticket_stock(ticket_tier_id,session_id,total_quantity,available_quantity) VALUES(1,1,500,500)");}
        inventoryProxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        inventoryProxy.setExecutor(workers);inventoryProxy.createContext("/",e->forward(e,true));
        inventoryProxy.start();inventoryProxyPort=inventoryProxy.getAddress().getPort();
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        proxy.setExecutor(workers);proxy.createContext("/",e->forward(e,false));proxy.start();proxyPort=proxy.getAddress().getPort();
        orderPort=freePort();paymentPort=freePort();
        paymentProcess=launchPayment(false);waitPing(paymentProcess,paymentPort,"/api/payments/ping");
        // 先启动到 V2，再以新版本升级，覆盖上一阶段已有数据的迁移。
        orderProcess=launchOrder(List.of("--spring.flyway.target=2","--ticket.order.recovery-enabled=false"));
        waitPing(orderProcess,orderPort,"/api/orders/ping");
        String legacy=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));String legacyPayment=create(legacy);pay(legacyPayment);
        try(var sql=orderDb.prepareStatement("UPDATE t_order SET status='REVIEW_REQUIRED',payment_no=?,paid_at=? WHERE order_no=?")){
            sql.setString(1,legacyPayment);sql.setTimestamp(2,Timestamp.valueOf(LocalDateTime.parse(value(paymentDb,"paid_at",legacy).replace(' ','T'))));
            sql.setString(3,legacy);sql.executeUpdate();
        }
        stop(orderProcess);orderPort=freePort();orderProcess=launchOrder(List.of());waitPing(orderProcess,orderPort,"/api/orders/ping");
        awaitState(legacy,"PAID");check(stockStatus(legacy).equals("SOLD"),"legacy payment-review order recovers after V3 migration");
        normal();lostConfirm();lostReversal();failures();databaseRecovery();latePayment();competition(true);competition(false);restart();
        check(stockValue("total_quantity")==stockValue("available_quantity")+stockValue("reserved_quantity")+stockValue("sold_quantity"),"stock balance maintained");
        check(stockValue("reserved_quantity")==0,"all test reservations reach terminal state");
    }
    Process launchPayment(boolean notifications)throws Exception{
        return start("ticket-payment-service",paymentPort,paymentSchema,List.of("--ticket.payment.simulation-enabled=true",
                "--ticket.payment.dev-identity-enabled=true","--ticket.payment.notification.enabled="+notifications,
                "--ticket.payment.notification.fixed-delay=150",
                "--spring.cloud.discovery.client.simple.instances.ticket-order-service[0].uri=http://127.0.0.1:"+orderPort));
    }
    Process launchOrder(List<String> extra)throws Exception{
        var args=new ArrayList<>(List.of("--ticket.order.dev-identity-enabled=true","--ticket.order.recovery-delay-ms=150",
                "--ticket.order.retry-base=500ms","--ticket.order.retry-max=1s","--ticket.order.lease-duration=60s",
                "--ticket.clients.inventory.connect-timeout=300ms","--ticket.clients.inventory.read-timeout=700ms",
                "--ticket.clients.payment.connect-timeout=300ms","--ticket.clients.payment.read-timeout=700ms",
                "--spring.cloud.discovery.client.simple.instances.ticket-inventory-service[0].uri=http://127.0.0.1:"+inventoryProxyPort,
                "--spring.cloud.discovery.client.simple.instances.ticket-payment-service[0].uri=http://127.0.0.1:"+proxyPort));
        args.addAll(extra);return start("ticket-order-service",orderPort,orderSchema,args);
    }
    @Override String fixture(String status,Long owner,boolean reservation,LocalDateTime expires)throws Exception{
        String no=super.fixture(status,owner,reservation,expires);
        if(reservation){
            var response=request("POST",stockPort,"/internal/stock-reservations",null,
                    "{\"orderId\":\""+no+"\",\"sessionId\":1,\"ticketTierId\":1,\"quantity\":2,\"expiresAt\":\""+expires+"\"}");
            check(response.statusCode()==200,"real stock reservation created");
            update("UPDATE t_order SET reservation_id=? WHERE order_no=?",data(response).path("reservationId").asString(),no);
        }return no;
    }
    String create(String order)throws Exception{
        var response=post(orderPort,order,1L,null);check(response.statusCode()==200,"real payment created");return data(response).path("paymentNo").asString();
    }
    HttpResponse<String> pay(String number)throws Exception{
        var response=request("POST",paymentPort,"/api/payments/"+number+"/simulate-success",1L,null);
        check(response.statusCode()==200,"real payment success committed");return response;
    }
    void notifyOrder(String order,String payment)throws Exception{
        check(request("POST",orderPort,"/internal/orders/payment-results",null,
                "{\"orderNo\":\""+order+"\",\"paymentNo\":\""+payment+"\"}").statusCode()==200,"payment evidence acknowledged");
    }
    String ready()throws Exception{return fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));}
    void release(String order)throws Exception{
        check(request("POST",stockPort,"/internal/stock-reservations/"+reservation(order)+"/release",null,null).statusCode()==200,"real reservation released");
        update("UPDATE t_order SET status='CLOSED' WHERE order_no=?",order);
    }
    void normal()throws Exception{
        String order=ready();String payment=create(order);pay(payment);notifyOrder(order,payment);awaitState(order,"PAID");
        check(stockStatus(order).equals("SOLD"),"paid order has SOLD stock");
        int before=confirms.get();List<Future<?>> duplicates=new ArrayList<>();
        for(int i=0;i<12;i++)duplicates.add(workers.submit(()->{notifyOrder(order,payment);return null;}));
        for(var task:duplicates)task.get(15,TimeUnit.SECONDS);
        check(orderStatus(order).equals("COMPLETED")&&confirms.get()==before,"duplicate notification cannot reopen completed order or sell again");
        check(reversalCount(order)==0,"normal payment does not reverse");
        System.out.println("PASS paid order, real sold stock and duplicate notifications");
    }
    void lostConfirm()throws Exception{
        inventoryFault.set("CONFIRM_LOST");int before=confirms.get();
        String order=ready();String payment=create(order);pay(payment);notifyOrder(order,payment);
        await(()->stockStatus(order).equals("SOLD")&&orderStatus(order).equals("PAYMENT_CONFIRMING"),"sold inventory despite lost response");
        inventoryFault.set("PASS");awaitState(order,"PAID");
        check(confirms.get()==before+1,"recovery queries sold record without another stock mutation");
        System.out.println("PASS committed-but-lost stock confirmation response");
    }
    void lostReversal()throws Exception{
        String order=ready();String payment=create(order);pay(payment);release(order);
        paymentFault.set("REVERSAL_LOST");int before=reversals.get();notifyOrder(order,payment);
        await(()->reversalCount(order)==1&&orderStatus(order).equals("REVERSAL_PENDING"),"reversal committed despite lost response");
        paymentFault.set("PASS");awaitState(order,"REVERSED");
        check(reversals.get()==before+1&&reversalCount(order)==1,"recovery reuses original reversal");
        check(stockStatus(order).equals("RELEASED"),"reversed order cannot have sold stock");
        check(value(orderDb,"reversal_no",order)!=null,"reversal evidence saved on order");
        System.out.println("PASS released stock and committed-but-lost reversal response");
    }
    void failures()throws Exception{
        inventoryFault.set("503");String order=ready();String payment=create(order);pay(payment);notifyOrder(order,payment);
        await(()->value(orderDb,"last_error",order)!=null,"inventory outage retry recorded");
        check(orderStatus(order).equals("PAYMENT_CONFIRMING"),"inventory outage preserves paid progress");
        inventoryFault.set("PASS");awaitState(order,"PAID");
        String reversed=ready();String p=create(reversed);pay(p);release(reversed);paymentFault.set("REVERSAL_503");notifyOrder(reversed,p);
        await(()->orderStatus(reversed).equals("REVERSAL_PENDING")&&value(orderDb,"last_error",reversed)!=null,"reversal outage persisted");
        check(reversalCount(reversed)==0,"outage does not claim refund complete");
        paymentFault.set("PASS");awaitState(reversed,"REVERSED");
        String invalid=ready();String ip=create(invalid);pay(ip);inventoryFault.set("WRONG_ORDER");int before=confirms.get();notifyOrder(invalid,ip);
        awaitState(invalid,"REVIEW_REQUIRED");check(confirms.get()==before&&stockStatus(invalid).equals("RESERVED"),"foreign reservation is never confirmed/refunded");
        inventoryFault.set("PASS");
        // 异常夹具人工释放，仍保留付款依据；不把人工核对状态伪装成已退款。
        request("POST",stockPort,"/internal/stock-reservations/"+reservation(invalid)+"/release",null,null);
        System.out.println("PASS transient outages and mismatched inventory evidence");
    }
    void databaseRecovery()throws Exception{
        for(String terminal:List.of("PAID","REVERSED")){
            String order=ready();String payment=create(order);pay(payment);if(terminal.equals("REVERSED"))release(order);
            try(var sql=orderDb.createStatement()){
                sql.execute("CREATE TRIGGER fail_terminal BEFORE UPDATE ON t_order FOR EACH ROW BEGIN "
                        +"IF NEW.order_no='"+order+"' AND NEW.status='"+terminal+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected terminal failure'; END IF; END");
            }
            notifyOrder(order,payment);await(()->value(orderDb,"last_error",order)!=null,"terminal save failure persisted");
            check(stockStatus(order).equals(terminal.equals("PAID")?"SOLD":"RELEASED"),"remote inventory fact survives failed order save");
            if(terminal.equals("REVERSED"))check(reversalCount(order)==1,"remote reversal survives failed terminal save");
            try(var sql=orderDb.createStatement()){sql.execute("DROP TRIGGER fail_terminal");}
            awaitState(order,terminal);
        }
        System.out.println("PASS local order save failure after sold stock and after reversal");
    }
    void latePayment()throws Exception{
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusSeconds(4));String payment=create(order);pay(payment);
        // 故意不发通知，到期任务仍回查支付，恢复付款依据而不释放已付款库存。
        awaitState(order,"PAID");check(stockStatus(order).equals("SOLD")&&reversalCount(order)==0,"expiry query recovers missing payment notification");
        notifyOrder(order,payment);check(orderStatus(order).equals("COMPLETED"),"late notification leaves final progress intact");
        System.out.println("PASS expiry recovery of successful payment without notification");
    }
    void competition(boolean releaseWins)throws Exception{
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusSeconds(4));String payment=create(order);
        gate=new Gate(reservation(order));Gate current=gate;
        try(var sql=paymentDb.createStatement()){
            // paidAt在Java层记录为期限内，但事务故意跨过到期时间；旧关闭任务能读到CREATED。
            sql.execute("CREATE TRIGGER delayed_payment BEFORE UPDATE ON t_payment FOR EACH ROW BEGIN "
                    +"IF NEW.payment_no='"+payment+"' AND OLD.status='CREATED' AND NEW.status='SUCCESS' THEN DO SLEEP(5); END IF; END");
        }
        var paying=workers.submit(()->pay(payment));
        check(current.releaseEntered.await(12,TimeUnit.SECONDS),"old close has sent release before payment commit");
        paying.get(15,TimeUnit.SECONDS);notifyOrder(order,payment);
        check(current.confirmEntered.await(10,TimeUnit.SECONDS),"new paid workflow sends confirmation");
        if(releaseWins){current.allowRelease.countDown();await(()->stockStatus(order).equals("RELEASED"),"old release wins terminal stock");current.allowConfirm.countDown();awaitState(order,"REVERSED");}
        else{current.allowConfirm.countDown();await(()->stockStatus(order).equals("SOLD"),"new confirmation wins terminal stock");current.allowRelease.countDown();awaitState(order,"PAID");}
        check(orderStatus(order).equals(releaseWins?"REVERSED":"COMPLETED"),"close/confirm competition has consistent order outcome");
        check(reversalCount(order)==(releaseWins?1:0),"only released stock results in reversal");
        try(var sql=paymentDb.createStatement()){sql.execute("DROP TRIGGER delayed_payment");}
        gate=null;System.out.println("PASS in-flight close versus confirm: "+(releaseWins?"release wins, reversed":"confirm wins, paid"));
    }
    void restart()throws Exception{
        String order=ready();String payment=create(order);pay(payment);release(order);paymentFault.set("REVERSAL_503");notifyOrder(order,payment);
        await(()->orderStatus(order).equals("REVERSAL_PENDING")&&value(orderDb,"last_error",order)!=null,"durable reversal before restart");
        stop(orderProcess);orderPort=freePort();paymentFault.set("PASS");orderProcess=launchOrder(List.of());waitPing(orderProcess,orderPort,"/api/orders/ping");
        awaitState(order,"REVERSED");check(reversalCount(order)==1,"restarted order recovers one original reversal");
        int secondPort=freePort();int savedPort=orderPort;orderPort=secondPort;Process second=launchOrder(List.of());waitPing(second,secondPort,"/api/orders/ping");orderPort=savedPort;
        stop(paymentProcess);paymentPort=freePort();paymentProcess=launchPayment(true);waitPing(paymentProcess,paymentPort,"/api/payments/ping");
        String automatic=ready();String ap=create(automatic);pay(ap);awaitState(automatic,"PAID");
        await(()->"DELIVERED".equals(value(paymentDb,"notify_status",automatic)),"real automatic payment notification delivered");
        check(stockStatus(automatic).equals("SOLD"),"two order workers preserve sold stock");stop(second);
        System.out.println("PASS restart recovery, two order workers and automatic notification chain");
    }
    void forward(HttpExchange exchange,boolean inventory)throws java.io.IOException{
        String path=exchange.getRequestURI().getPath();byte[] body=exchange.getRequestBody().readAllBytes();
        String fault=(inventory?inventoryFault:paymentFault).get();
        try{
            if(fault.equals("503")||fault.equals("REVERSAL_503")&&path.equals("/internal/payment-reversals")){
                send(exchange,503,"{\"code\":\"UPSTREAM_UNAVAILABLE\"}",null);return;
            }
            if(inventory&&path.endsWith("/confirm")){confirms.incrementAndGet();}
            if(!inventory&&path.equals("/internal/payment-reversals")){reversals.incrementAndGet();}
            Gate held=gate;
            if(inventory&&held!=null&&path.contains(held.reservation)){
                if(path.endsWith("/release")){held.releaseEntered.countDown();held.allowRelease.await(20,TimeUnit.SECONDS);}
                if(path.endsWith("/confirm")){held.confirmEntered.countDown();held.allowConfirm.await(20,TimeUnit.SECONDS);}
            }
            var response=request(exchange.getRequestMethod(),inventory?stockPort:paymentPort,path,null,
                    body.length==0?null:new String(body,StandardCharsets.UTF_8));
            if(inventory&&fault.equals("WRONG_ORDER")&&exchange.getRequestMethod().equals("GET")&&response.statusCode()==200){
                var tree=(ObjectNode)JSON.readTree(response.body());((ObjectNode)tree.path("data")).put("orderId",newNo());
                send(exchange,200,JSON.writeValueAsString(tree),null);return;
            }
            if(inventory&&fault.equals("CONFIRM_LOST")&&path.endsWith("/confirm")
                    ||!inventory&&fault.equals("REVERSAL_LOST")&&path.equals("/internal/payment-reversals")){Thread.sleep(1700);}
            send(exchange,response.statusCode(),response.body(),null);
        }catch(Exception failed){exchange.close();}finally{exchange.close();}
    }
    String reservation(String order)throws Exception{return value(orderDb,"reservation_id",order);}
    String stockStatus(String order)throws Exception{
        try(var sql=stockDb.prepareStatement("SELECT status FROM t_stock_reservation WHERE order_id=?")){sql.setString(1,order);try(var rows=sql.executeQuery()){rows.next();return rows.getString(1);}}
    }
    int stockValue(String column)throws Exception{try(var sql=stockDb.createStatement();var rows=sql.executeQuery("SELECT "+column+" FROM t_ticket_stock WHERE ticket_tier_id=1")){rows.next();return rows.getInt(1);}}
    int reversalCount(String order)throws Exception{
        try(var sql=paymentDb.prepareStatement("SELECT COUNT(*) FROM t_payment_reversal r JOIN t_payment p ON p.id=r.payment_id WHERE p.order_no=?")){sql.setString(1,order);try(var rows=sql.executeQuery()){rows.next();return rows.getInt(1);}}
    }
    String value(Connection db,String column,String order)throws Exception{
        try(var sql=db.prepareStatement("SELECT "+column+" FROM "+(db==orderDb?"t_order":"t_payment")+" WHERE order_no=?")){sql.setString(1,order);try(var rows=sql.executeQuery()){rows.next();return rows.getString(1);}}
    }
    void update(String query,String...args)throws Exception{try(var sql=orderDb.prepareStatement(query)){for(int i=0;i<args.length;i++)sql.setString(i+1,args[i]);sql.executeUpdate();}}
    void awaitState(String order,String status)throws Exception{
        String target=status.equals("PAID")?"COMPLETED":status;
        await(()->orderStatus(order).equals(target),"order reaches "+target);
        if(target.equals("COMPLETED")) {
            var tickets=data(request("GET",orderPort,"/api/orders/"+order+"/tickets",1L,null));
            check(tickets.path("orderStatus").asString().equals("COMPLETED")&&tickets.path("tickets").size()==2,
                    "real paid/sold order issues two tickets");
        } else if(target.equals("REVERSED")) {
            check(data(request("GET",orderPort,"/api/orders/"+order+"/tickets",1L,null)).path("tickets").isEmpty(),
                    "reversed order issues no tickets");
        }
    }
    interface Condition{boolean evaluate()throws Exception;}
    void await(Condition condition,String name)throws Exception{for(int i=0;i<220;i++){if(condition.evaluate()){check(true,name);return;}Thread.sleep(100);}throw new AssertionError(name+"; logs "+logs);}
    void waitPing(Process process,int port,String path,int expected)throws Exception{
        for(int i=0;i<160;i++){if(!process.isAlive())throw new AssertionError("service exited; "+logs);try{if(request("GET",port,path,null,null).statusCode()==expected)return;}catch(java.io.IOException ignored){}Thread.sleep(150);}throw new AssertionError("startup timeout");
    }
    void stop(Process process)throws Exception{process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();}}
    @Override void cleanup()throws Exception{
        if(gate!=null){gate.allowConfirm.countDown();gate.allowRelease.countDown();}
        if(inventoryProxy!=null)inventoryProxy.stop(0);if(stockDb!=null)stockDb.close();super.cleanup();
    }
}
