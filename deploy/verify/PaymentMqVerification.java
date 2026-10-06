import com.sun.net.httpserver.*;
import com.rabbitmq.client.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** 真实RabbitMQ、Payment/Order/Inventory与隔离数据库闭环及故障验证。 */
public class PaymentMqVerification extends PaymentFulfillmentVerification {
    final String prefix="ticket_verify_"+suffix;
    com.rabbitmq.client.Connection broker;
    Channel mq;
    PaymentMqVerification(Path root)throws Exception{super(root);}
    public static void main(String[] args)throws Exception {
        var test=new PaymentMqVerification(Path.of(args[0]).toAbsolutePath());
        try{test.runMq();}finally{test.cleanup();}
        System.out.println("PASS payment MQ: "+test.checks+" checks; isolated queues, databases and processes removed.");
    }
    void runMq()throws Exception {
        user=requireEnv("LOCAL_MYSQL_USERNAME");password=requireEnv("LOCAL_MYSQL_PASSWORD");
        var factory=new ConnectionFactory();
        factory.setHost(env("LOCAL_RABBITMQ_HOST","127.0.0.1"));
        factory.setPort(Integer.parseInt(env("LOCAL_RABBITMQ_PORT","5672")));
        factory.setUsername(env("LOCAL_RABBITMQ_USERNAME","guest"));
        factory.setPassword(env("LOCAL_RABBITMQ_PASSWORD","guest"));
        factory.setVirtualHost(env("LOCAL_RABBITMQ_VHOST","/"));
        broker=factory.newConnection();mq=broker.createChannel();mq.confirmSelect();
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",user,password);
        try(var sql=admin.createStatement()){
            for(String schema:List.of(orderSchema,paymentSchema,stockSchema)){
                sql.execute("CREATE DATABASE "+schema);createdSchemas.add(schema);
            }
        }
        orderDb=DriverManager.getConnection(dbUrl(orderSchema),user,password);
        paymentDb=DriverManager.getConnection(dbUrl(paymentSchema),user,password);
        stockDb=DriverManager.getConnection(dbUrl(stockSchema),user,password);
        stockPort=freePort();var stock=start("ticket-inventory-service",stockPort,stockSchema,List.of());
        waitPing(stock,stockPort,"/internal/stocks/1",404);
        try(var sql=stockDb.createStatement()){sql.execute("INSERT INTO t_ticket_stock(ticket_tier_id,session_id,total_quantity,available_quantity) VALUES(1,1,500,500)");}
        inventoryProxy=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        inventoryProxy.setExecutor(workers);inventoryProxy.createContext("/",e->forward(e,true));inventoryProxy.start();
        inventoryProxyPort=inventoryProxy.getAddress().getPort();
        paymentPort=freePort();orderPort=freePort();
        paymentProcess=launchPayment(false);waitPing(paymentProcess,paymentPort,"/api/payments/ping");
        proxy=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        proxy.setExecutor(workers);proxy.createContext("/",e->forward(e,false));proxy.start();proxyPort=proxy.getAddress().getPort();
        orderProcess=launchOrder(List.of());waitPing(orderProcess,orderPort,"/api/orders/ping");
        normalMq();duplicateAndPoison();temporaryFailure();atomicFailure();lateMq();lostPublishedProgress();
        unroutable();retryExhaustion();failedForwarding();replayTool();
    }
    @Override Process launchPayment(boolean ignored)throws Exception {
        return start("ticket-payment-service",paymentPort,paymentSchema,List.of(
                "--ticket.payment.simulation-enabled=true","--ticket.payment.dev-identity-enabled=true",
                "--ticket.payment.notification.enabled=false","--ticket.mq.enabled=true",
                "--ticket.mq.prefix="+prefix,"--ticket.mq.retry-delays-ms=300,600,1200","--ticket.mq.outbox-delay-ms=150"));
    }
    @Override Process launchOrder(List<String> extra)throws Exception {
        var arguments=new ArrayList<>(extra);
        arguments.addAll(List.of("--ticket.mq.enabled=true","--ticket.mq.prefix="+prefix,
                "--ticket.mq.retry-delays-ms=300,600,1200"));
        return super.launchOrder(arguments);
    }
    void normalMq()throws Exception {
        String order=ready(),payment=create(order);pay(payment);awaitState(order,"PAID");
        await(()->outboxState(payment).equals("PUBLISHED"),"broker confirmation marks outbox published");
        check(consumed(order)==1,"MQ commits payment receipt and consumer marker");
        check(value(paymentDb,"notify_status",order).equals("PENDING"),"HTTP sender stayed disabled; MQ completed fulfillment");
        check(stockStatus(order).equals("SOLD"),"MQ payment sells reserved inventory");
        System.out.println("PASS MQ payment, sold stock and two unique electronic tickets");
    }
    void duplicateAndPoison()throws Exception {
        String order=ready(),payment=create(order);pay(payment);awaitState(order,"PAID");
        byte[] original=payload(payment);
        for(int i=0;i<12;i++)send(original,eventId(payment));
        awaitEmpty();
        check(consumed(order)==1&&orderStatus(order).equals("COMPLETED"),"12 duplicate messages preserve one receipt and completed order");
        long deadBefore=mq.messageCount(prefix+".order.payment-dead");
        var changed=JSON.readTree(original).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)changed).put("orderNo",newNo());
        send(JSON.writeValueAsBytes(changed),eventId(payment));
        send("{bad".getBytes(StandardCharsets.UTF_8),newNo());
        await(()->mq.messageCount(prefix+".order.payment-dead")>=deadBefore+2,"invalid messages preserved in dead queue");
        check(consumed(order)==1,"poison payload cannot change original receipt");
    }
    void temporaryFailure()throws Exception {
        paymentFault.set("503");
        String order=ready();
        // 创建支付单也经过代理，因此创建时临时恢复代理。
        paymentFault.set("PASS");String payment=create(order);paymentFault.set("503");pay(payment);
        await(()->mq.messageCount(prefix+".order.payment-retry-1")>0,"temporary payment outage routes to delayed retry");
        check(consumed(order)==0&&orderStatus(order).equals("PENDING_PAYMENT"),"failed fact lookup cannot ACK a business receipt");
        paymentFault.set("PASS");awaitState(order,"PAID");check(consumed(order)==1,"retry recovers one durable receipt");
        System.out.println("PASS delayed MQ retry and authoritative payment lookup");
    }
    void atomicFailure()throws Exception {
        String order=ready(),payment=create(order);
        try(var sql=orderDb.createStatement()){
            sql.execute("CREATE TRIGGER mq_fail_order BEFORE UPDATE ON t_order FOR EACH ROW BEGIN "
                    +"IF NEW.payment_no IS NOT NULL AND OLD.payment_no IS NULL THEN "
                    +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='mq verification transaction failure'; END IF; END");
        }
        try{
            pay(payment);await(()->mq.messageCount(prefix+".order.payment-retry-1")>0,"order write failure goes to retry");
            check(consumed(order)==0&&value(orderDb,"payment_no",order)==null,"failed order transaction rolls consumer marker back");
        }finally{try(var sql=orderDb.createStatement()){sql.execute("DROP TRIGGER mq_fail_order");}}
        awaitState(order,"PAID");check(consumed(order)==1,"transaction retry commits receipt and marker together");
    }
    void lateMq()throws Exception {
        String order=ready(),payment=create(order);release(order);pay(payment);awaitState(order,"REVERSED");
        check(consumed(order)==1&&reversalCount(order)==1,"MQ late payment reuses original compensation path");
    }
    void lostPublishedProgress()throws Exception {
        String order=ready(),payment=create(order);pay(payment);awaitState(order,"PAID");
        await(()->outboxState(payment).equals("PUBLISHED"),"outbox first publish complete");
        try(var sql=paymentDb.prepareStatement("UPDATE t_outbox_event SET status='SENDING',published_at=NULL,lease_token=?,lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE aggregate_id=?")){
            sql.setString(1,newNo());sql.setString(2,payment);sql.executeUpdate();
        }
        await(()->outboxState(payment).equals("PUBLISHED"),"expired sender lease republishes original event");
        awaitEmpty();check(consumed(order)==1,"publish-confirm then state-write loss remains idempotent");
        stop(orderProcess);orderProcess=launchOrder(List.of());waitPing(orderProcess,orderPort,"/api/orders/ping");
        send(payload(payment),eventId(payment));awaitEmpty();
        check(consumed(order)==1&&orderStatus(order).equals("COMPLETED"),"consumer restart preserves durable deduplication");
    }
    void failedForwarding()throws Exception {
        String order=ready(),payment=create(order);
        mq.queueUnbind(prefix+".order.payment-retry-1",prefix+".payment.exchange","payment.retry.1");
        paymentFault.set("503");
        try {
            pay(payment);Thread.sleep(1800);
            check(consumed(order)==0,"failed retry transfer does not commit payment evidence");
        }finally {
            paymentFault.set("PASS");
            mq.queueBind(prefix+".order.payment-retry-1",prefix+".payment.exchange","payment.retry.1");
        }
        awaitState(order,"PAID");check(consumed(order)==1,"unconfirmed transfer retains original message for recovery");
        System.out.println("PASS original message retention when retry publish is returned");
    }
    void replayTool()throws Exception {
        String toolPrefix=prefix+"_replay";
        try {
            mq.exchangeDeclare(toolPrefix+".payment.exchange","direct",true);
            mq.queueDeclare(toolPrefix+".order.payment-dead",true,false,false,Map.of("x-queue-type","quorum"));
            mq.queueDeclare(toolPrefix+".order.payment-succeeded",true,false,false,Map.of("x-queue-type","quorum"));
            mq.queueBind(toolPrefix+".order.payment-succeeded",toolPrefix+".payment.exchange","payment.succeeded");
            mq.basicPublish("",toolPrefix+".order.payment-dead",new AMQP.BasicProperties.Builder()
                    .deliveryMode(2).messageId(newNo()).build(),"{}".getBytes(StandardCharsets.UTF_8));
            mq.waitForConfirmsOrDie(5000);
            for(boolean replay:List.of(false,true)) {
                var arguments=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),
                        "-cp",System.getProperty("java.class.path"),"PaymentDeadLetterReplay"));
                if(replay)arguments.add("--replay");
                var builder=new ProcessBuilder(arguments).redirectErrorStream(true);
                builder.environment().put("TICKET_MQ_PREFIX",toolPrefix);
                var process=builder.start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                check(process.waitFor()==0,"dead-letter tool runs: "+output);
                check(mq.messageCount(toolPrefix+".order.payment-dead")== (replay?0:1),
                        replay?"confirmed replay removes source dead letter":"preview retains source dead letter");
            }
            check(mq.messageCount(toolPrefix+".order.payment-succeeded")==1,"replay publishes one retained original message");
        }finally {
            mq.queueDelete(toolPrefix+".order.payment-dead");
            mq.queueDelete(toolPrefix+".order.payment-succeeded");
            mq.exchangeDelete(toolPrefix+".payment.exchange");
        }
    }
    void unroutable()throws Exception {
        mq.queueUnbind(prefix+".order.payment-succeeded",prefix+".payment.exchange","payment.succeeded");
        String order=ready(),payment=create(order);
        try {
            pay(payment);
            await(()->outboxState(payment).equals("PENDING") && !eventColumn(payment,"last_error").isBlank(),
                    "mandatory Return preserves unpublished event for retry");
            check(consumed(order)==0,"unroutable event does not claim order acceptance");
        } finally {
            mq.queueBind(prefix+".order.payment-succeeded",prefix+".payment.exchange","payment.succeeded");
        }
        awaitState(order,"PAID");
        await(()->outboxState(payment).equals("PUBLISHED"),"restored routing recovers original outbox event");
        System.out.println("PASS mandatory Return and durable sender retry");
    }
    void retryExhaustion()throws Exception {
        String order=ready(),payment=create(order);
        long before=mq.messageCount(prefix+".order.payment-dead");
        paymentFault.set("503");
        try {
            pay(payment);
            await(()->mq.messageCount(prefix+".order.payment-dead")>before,"three delayed retries exhaust into dead queue");
            check(consumed(order)==0&&value(orderDb,"payment_no",order)==null,
                    "exhausted retry retains message without fake payment evidence");
        } finally { paymentFault.set("PASS"); }
        // 操作人员确认故障已恢复后用原事件身份重投，不伪造新的付款。
        send(payload(payment),eventId(payment));awaitState(order,"PAID");
        check(consumed(order)==1,"reviewed redrive recovers one receipt");
    }
    void send(byte[] body,String id)throws Exception {
        mq.basicPublish(prefix+".payment.exchange","payment.succeeded",true,
                new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).messageId(id)
                        .headers(Map.of("X-Trace-Id",TRACE)).build(),body);
        mq.waitForConfirmsOrDie(5000);
    }
    String eventId(String payment)throws Exception{return eventColumn(payment,"event_id");}
    byte[] payload(String payment)throws Exception{return eventColumn(payment,"payload").getBytes(StandardCharsets.UTF_8);}
    String outboxState(String payment)throws Exception{return eventColumn(payment,"status");}
    String eventColumn(String payment,String column)throws Exception{
        try(var sql=paymentDb.prepareStatement("SELECT "+column+" FROM t_outbox_event WHERE aggregate_id=?")){
            sql.setString(1,payment);try(var rows=sql.executeQuery()){
                String value=rows.next()?rows.getString(1):null;return value==null?"":value;
            }
        }
    }
    int consumed(String order)throws Exception{
        try(var sql=orderDb.prepareStatement("SELECT COUNT(*) FROM t_consumed_event WHERE order_no=?")){
            sql.setString(1,order);try(var rows=sql.executeQuery()){rows.next();return rows.getInt(1);}
        }
    }
    void awaitEmpty()throws Exception {
        Thread.sleep(350);
        await(()->mq.messageCount(prefix+".order.payment-succeeded")==0,"main queue drains");
        Thread.sleep(350);
    }
    static String env(String key,String fallback){String value=System.getenv(key);return value==null||value.isBlank()?fallback:value;}
    @Override void cleanup()throws Exception {
        try{super.cleanup();}finally{
            if(broker!=null){
                try{
                    if(mq!=null&&mq.isOpen()){
                        for(String queue:List.of(".order.payment-succeeded",".order.payment-dead",
                                ".order.payment-retry-1",".order.payment-retry-2",".order.payment-retry-3"))
                            mq.queueDelete(prefix+queue);
                        mq.exchangeDelete(prefix+".payment.exchange");
                    }
                }finally{broker.close();}
            }
        }
    }
}
