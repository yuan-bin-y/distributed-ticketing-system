import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 复用独立进程与随机数据库夹具；真实 HTTP 回查支付，代理仅对通知注入故障。 */
public class PaymentNotificationVerification extends OrderPaymentVerification {
    final AtomicReference<String> notificationFault = new AtomicReference<>("PASS");
    final AtomicInteger notices = new AtomicInteger();
    final AtomicReference<String> notificationTrace = new AtomicReference<>();
    Process orderProcess, paymentProcess;

    PaymentNotificationVerification(Path root) throws Exception { super(root); }

    public static void main(String[] args) throws Exception {
        var test = new PaymentNotificationVerification(Path.of(args[0]).toAbsolutePath());
        try { test.runNotifications(); } finally { test.cleanup(); }
        System.out.println("PASS payment notifications: " + test.checks
                + " checks; isolated databases and owned processes removed.");
    }

    void runNotifications() throws Exception {
        user=requireEnv("LOCAL_MYSQL_USERNAME"); password=requireEnv("LOCAL_MYSQL_PASSWORD");
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",user,password);
        try(var sql=admin.createStatement()) {
            for(String schema:List.of(orderSchema,paymentSchema)) {
                sql.execute("CREATE DATABASE "+schema);createdSchemas.add(schema);
            }
        }
        orderDb=DriverManager.getConnection(dbUrl(orderSchema),user,password);
        paymentDb=DriverManager.getConnection(dbUrl(paymentSchema),user,password);
        orderPort=freePort(); paymentPort=freePort();
        proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        proxy.setExecutor(workers); proxy.createContext("/",this::notificationProxy);
        proxy.start(); proxyPort=proxy.getAddress().getPort();
        // 先仅迁移至 V1，再升级到 V2，验证已有数据库的升级而不修改旧迁移。
        orderProcess=launchOrder(List.of("--spring.flyway.target=1"));
        waitPing(orderProcess,orderPort,"/api/orders/ping"); stop(orderProcess);
        paymentProcess=launchPayment(paymentPort,List.of("--spring.flyway.target=1",
                "--ticket.payment.notification.enabled=false"));
        waitPing(paymentProcess,paymentPort,"/api/payments/ping"); stop(paymentProcess);
        orderProcess=launchOrder(List.of());waitPing(orderProcess,orderPort,"/api/orders/ping");
        paymentProcess=launchPayment(paymentPort,List.of());waitPing(paymentProcess,paymentPort,"/api/payments/ping");
        check(scalar(orderDb,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")==2,"order V1 upgraded to V2");
        check(scalar(paymentDb,"SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")==2,"payment V1 upgraded to V2");
        normal(); duplicateAndForgery(); failedAndLost(); rollback(); closeCompetition(); restartAndConcurrency();
    }

    Process launchOrder(List<String> extra) throws Exception {
        var args=new ArrayList<>(List.of("--ticket.order.dev-identity-enabled=true",
                "--ticket.order.recovery-enabled=false","--ticket.clients.payment.read-timeout=2s",
                "--spring.cloud.discovery.client.simple.instances.ticket-payment-service[0].uri=http://127.0.0.1:"+paymentPort));
        args.addAll(extra);return start("ticket-order-service",orderPort,orderSchema,args);
    }
    Process launchPayment(int port,List<String> extra) throws Exception {
        var args=new ArrayList<>(List.of("--ticket.payment.simulation-enabled=true","--ticket.payment.dev-identity-enabled=true",
                "--ticket.payment.notification.fixed-delay=150","--ticket.payment.notification.retry-base=300ms",
                "--ticket.payment.notification.retry-max=1s","--ticket.payment.notification.connect-timeout=1s",
                "--ticket.payment.notification.read-timeout=1s",
                "--spring.cloud.discovery.client.simple.instances.ticket-order-service[0].uri=http://127.0.0.1:"+proxyPort));
        args.addAll(extra);return start("ticket-payment-service",port,paymentSchema,args);
    }
    String create(String order) throws Exception {
        var result=post(orderPort,order,1L,null);
        check(result.statusCode()==200,"real order creates payment");
        return data(result).path("paymentNo").asString();
    }
    void pay(String number) throws Exception {
        check(request("POST",paymentPort,"/api/payments/"+number+"/simulate-success",1L,null).statusCode()==200,
                "real payment records success");
    }
    void normal() throws Exception {
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(5));String number=create(order);
        pay(number);awaitDelivered(order);
        check(orderStatus(order).equals("PAYMENT_CONFIRMING"),"receipt advances order without falsely completing stock");
        check(text(orderDb,"SELECT payment_no FROM t_order WHERE order_no=?",order).equals(number),"payment proof persisted");
        check(text(orderDb,"SELECT paid_at FROM t_order WHERE order_no=?",order)
                .equals(text(paymentDb,"SELECT paid_at FROM t_payment WHERE order_no=?",order)),"original paid time preserved");
        check(notificationTrace.get()!=null&&notificationTrace.get().matches("[0-9a-f]{32}"),"background notification propagates trace");
        check(scalar(orderDb,"SELECT COUNT(*) FROM t_order WHERE lease_token IS NOT NULL")==0,"receipt clears stale lease");
        System.out.println("PASS real payment notification, authoritative query and local payment evidence");
    }
    HttpResponse<String> notify(String order,String payment) throws Exception {
        return request("POST",orderPort,"/internal/orders/payment-results",null,
                "{\"orderNo\":\""+order+"\",\"paymentNo\":\""+payment+"\"}");
    }
    void duplicateAndForgery() throws Exception {
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(5));String number=create(order);
        check(notify(order,number).statusCode()==409,"unpaid payment cannot forge success");
        check(orderStatus(order).equals("PENDING_PAYMENT"),"forged notification leaves state unchanged");
        check(notify(order,newNo()).statusCode()==409,"wrong payment id rejected");
        check(notify(newNo(),newNo()).statusCode()==404,"missing payment fact rejected");
        check(notify("bad","bad").statusCode()==400,"invalid notification ids rejected");
        notificationFault.set("503");pay(number);
        update(orderDb,"UPDATE t_order SET total_amount=1 WHERE order_no=?",order);
        check(notify(order,number).statusCode()==409,"amount mismatch rejected");
        check(text(orderDb,"SELECT payment_no FROM t_order WHERE order_no=?",order)==null,"mismatch saves no proof");
        update(orderDb,"UPDATE t_order SET total_amount=398 WHERE order_no=?",order);
        List<Future<HttpResponse<String>>> calls=new ArrayList<>();
        for(int i=0;i<12;i++)calls.add(workers.submit(()->notify(order,number)));
        for(var call:calls)check(call.get(15,TimeUnit.SECONDS).statusCode()==200,"concurrent duplicate acknowledged");
        check(orderStatus(order).equals("PAYMENT_CONFIRMING"),"concurrent receipts preserve one progress state");
        check(scalar(orderDb,"SELECT COUNT(*) FROM t_order WHERE payment_no IS NOT NULL")==2,"one proof per order");
        notificationFault.set("PASS");awaitDelivered(order);
        System.out.println("PASS forged, inconsistent and concurrent duplicate notifications");
    }
    void failedAndLost() throws Exception {
        for(String fault:List.of("503","WRONG_ACK","LOST")) {
            notificationFault.set(fault);
            String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(5));String number=create(order);pay(number);
            await(()->notifyAttempts(order)>=1,"notification attempted");
            await(()->text(paymentDb,"SELECT last_notify_error FROM t_payment WHERE order_no=?",order)!=null,"retry error persisted");
            check(notifyStatus(order).equals("PENDING"),"failed/unknown acknowledgement not delivered: "+fault);
            if(fault.equals("LOST"))check(orderStatus(order).equals("PAYMENT_CONFIRMING"),"lost response follows committed receipt");
            notificationFault.set("PASS");awaitDelivered(order);
            check(orderStatus(order).equals("PAYMENT_CONFIRMING"),"retry preserves payment progress: "+fault);
            check(notifyAttempts(order)>=2,"failed notification retries: "+fault);
        }
        System.out.println("PASS upstream error, wrong acknowledgement and committed-but-lost response");
    }
    void rollback() throws Exception {
        notificationFault.set("503");
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(5));String number=create(order);pay(number);
        try(var sql=orderDb.createStatement()) {
            sql.execute("CREATE TRIGGER receipt_fail BEFORE UPDATE ON t_order FOR EACH ROW "
                    +"BEGIN IF NEW.payment_no IS NOT NULL AND OLD.payment_no IS NULL THEN "
                    +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected receipt rollback'; END IF; END");
        }
        check(notify(order,number).statusCode()>=500,"receipt failure returns no acknowledgement");
        check(text(orderDb,"SELECT payment_no FROM t_order WHERE order_no=?",order)==null,"failed receipt saves no proof");
        check(orderStatus(order).equals("PENDING_PAYMENT"),"failed receipt rolls back progress");
        try(var sql=orderDb.createStatement()){sql.execute("DROP TRIGGER receipt_fail");}
        notificationFault.set("PASS");awaitDelivered(order);
        check(orderStatus(order).equals("PAYMENT_CONFIRMING"),"retry succeeds after local transaction failure");
        System.out.println("PASS local transaction failure and reliable retry");
    }
    void closeCompetition() throws Exception {
        notificationFault.set("503");
        for(String state:List.of("CLOSING","CLOSED","REVIEW_REQUIRED")) {
            String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(5));String number=create(order);pay(number);
            String oldToken=newNo();
            update(orderDb,"UPDATE t_order SET status='"+state+"',lease_token='"+oldToken+"' WHERE order_no=?",order);
            check(notify(order,number).statusCode()==200,"late notification accepted for "+state);
            check(orderStatus(order).equals("REVIEW_REQUIRED"),"closed/closing order not revived or falsely paid");
            check(text(orderDb,"SELECT payment_no FROM t_order WHERE order_no=?",order).equals(number),"late payment evidence retained");
            check(text(orderDb,"SELECT lease_token FROM t_order WHERE order_no=?",order)==null,"old closer token invalidated");
            try(var sql=orderDb.prepareStatement("UPDATE t_order SET status='CLOSED' WHERE order_no=? AND lease_token=? AND status='CLOSING'")) {
                sql.setString(1,order);sql.setString(2,oldToken);
                check(sql.executeUpdate()==0,"old closer cannot overwrite saved payment evidence");
            }
        }
        var expiry=now().plusSeconds(4);
        String late=fixture("PENDING_PAYMENT",1L,true,expiry);String lateNumber=create(late);pay(lateNumber);
        await(()->now().isAfter(expiry),"notification arrives after payment deadline");
        check(notify(late,lateNumber).statusCode()==200,"on-time payment accepted despite late notification");
        check(orderStatus(late).equals("PAYMENT_CONFIRMING"),"paidAt rather than arrival time determines valid payment");
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(5));String number=create(order);pay(number);
        check(request("POST",paymentPort,"/internal/payment-reversals",null,
                "{\"paymentNo\":\""+number+"\",\"reason\":\"verification\"}").statusCode()==200,"reversal fact created");
        check(notify(order,number).statusCode()==200&&orderStatus(order).equals("REVIEW_REQUIRED"),"reversed payment cannot confirm sale");
        notificationFault.set("PASS");
        System.out.println("PASS closing competition, stale lease and reversal evidence");
    }
    void restartAndConcurrency() throws Exception {
        notificationFault.set("503");
        String order=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(5));String number=create(order);pay(number);
        await(()->notifyAttempts(order)>=1,"pending notification persisted before restart");
        stop(paymentProcess);stop(orderProcess);
        // Windows 可能暂时保留旧监听端口，恢复事实不依赖实例地址保持不变。
        orderPort=freePort();paymentPort=freePort();
        while(paymentPort==orderPort){paymentPort=freePort();}
        notificationFault.set("PASS");
        orderProcess=launchOrder(List.of());waitPing(orderProcess,orderPort,"/api/orders/ping");
        paymentProcess=launchPayment(paymentPort,List.of());waitPing(paymentProcess,paymentPort,"/api/payments/ping");
        awaitDelivered(order);
        check(orderStatus(order).equals("PAYMENT_CONFIRMING"),"restart resumes persisted payment notification");
        int secondPort=freePort();Process second=launchPayment(secondPort,List.of());
        waitPing(second,secondPort,"/api/payments/ping");
        String concurrent=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(5));String another=create(concurrent);pay(another);
        awaitDelivered(concurrent);
        check(notifyAttempts(concurrent)==1,"two payment senders claim one task within lease");
        check(orderStatus(concurrent).equals("PAYMENT_CONFIRMING"),"multi-instance notification saved once");
        stop(second);
        System.out.println("PASS restart recovery and multi-instance notification claim");
    }
    void notificationProxy(HttpExchange exchange) throws java.io.IOException {
        notices.incrementAndGet();notificationTrace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
        String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        String fault=notificationFault.get();
        try {
            if(fault.equals("503")){send(exchange,503,"{}",null);return;}
            if(fault.equals("WRONG_ACK")){send(exchange,200,"{\"code\":\"OK\",\"data\":{\"accepted\":true}}",null);return;}
            var received=request("POST",orderPort,"/internal/orders/payment-results",null,body);
            if(fault.equals("LOST"))Thread.sleep(1800);
            send(exchange,received.statusCode(),received.body(),null);
        }catch(Exception expected){exchange.close();}finally{exchange.close();}
    }
    String text(Connection db,String query,String no) throws Exception {
        try(var sql=db.prepareStatement(query)){sql.setString(1,no);try(var rows=sql.executeQuery()){rows.next();return rows.getString(1);}}
    }
    int scalar(Connection db,String query) throws Exception {
        try(var sql=db.createStatement();var rows=sql.executeQuery(query)){rows.next();return rows.getInt(1);}
    }
    void update(Connection db,String query,String no) throws Exception {
        try(var sql=db.prepareStatement(query)){sql.setString(1,no);sql.executeUpdate();}
    }
    String notifyStatus(String no) throws Exception {return text(paymentDb,"SELECT notify_status FROM t_payment WHERE order_no=?",no);}
    int notifyAttempts(String no) throws Exception {return Integer.parseInt(text(paymentDb,"SELECT notify_attempt_count FROM t_payment WHERE order_no=?",no));}
    void awaitDelivered(String no) throws Exception {await(()->"DELIVERED".equals(notifyStatus(no)),"notification eventually delivered");}
    interface Condition { boolean evaluate() throws Exception; }
    void await(Condition condition,String name) throws Exception {
        for(int i=0;i<180;i++){if(condition.evaluate()){check(true,name);return;}Thread.sleep(100);}
        throw new AssertionError(name+"; inspect "+logs);
    }
    void stop(Process process) throws Exception {process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();}}
}
