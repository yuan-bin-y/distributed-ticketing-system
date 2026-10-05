import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.http.*;
import org.flywaydb.core.Flyway;

/** 真实Order事务和MySQL并发限购；复用独立Auth/Inventory/Payment，仅活动规则为HTTP桩。 */
public class PurchaseQuotaVerification extends OrderAuthVerification {
    PurchaseQuotaVerification(Path root){super(root);}
    public static void main(String[] args)throws Exception{
        var test=new PurchaseQuotaVerification(Path.of(args[0]).toAbsolutePath());
        try{test.run();}finally{test.cleanup();}
        System.out.println("PASS purchase quota: "+test.checks+" checks; isolated databases, sessions and processes cleaned.");
    }
    @Override void run()throws Exception{
        super.run();
        check(quota(1,2)==2,"completed tickets continue to occupy quota");
        String buyer=login(port(first)).path("accessToken").asText();
        status(buy(orderPort,buyer,3,1,"completed_over_limit"),409);
        event.removeContext("/");
        event.createContext("/",exchange->{
            long tier=Long.parseLong(exchange.getRequestURI().getPath().split("/")[3]);long session=tier==5?6:2;
            var now=LocalDateTime.now(ZoneOffset.ofHours(8));
            byte[] body=JSON.writeValueAsBytes(Map.of("code","OK","message","success","data",Map.of(
                    "eventId",1,"sessionId",session,"ticketTierId",tier,"ticketTierName","测试票","price",199,
                    "saleStartTime",now.minusDays(1).toString(),"saleEndTime",now.plusDays(1).toString(),"purchaseLimit",2)));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        execute("INSERT INTO "+inventorySchema+".t_ticket_stock(ticket_tier_id,session_id,total_quantity,available_quantity) VALUES(4,2,20,20),(5,6,20,20)");
        String token=register("quotauser");long user=3;
        var gate=new CountDownLatch(1);var futures=new ArrayList<Future<HttpResponse<String>>>();
        for(int i=0;i<20;i++){int n=i;futures.add(pool.submit(()->{gate.await();return buy(orderPort,token,3,1,"quota_race_"+n);}));}
        gate.countDown();int winners=0;String winnerKey=null,winnerNo=null;
        for(int i=0;i<futures.size();i++){
            var result=futures.get(i).get(30,TimeUnit.SECONDS);
            if(result.statusCode()==200||result.statusCode()==202){winners++;winnerKey="quota_race_"+i;winnerNo=JSON.readTree(result.body()).path("data").path("orderNo").asText();}
            else status(result,409);
        }
        check(winners==2,"20 concurrent requests only acquire two tickets");
        check(quota(user,2)==2,"quota never exceeds two");
        check(number("SELECT COUNT(*) FROM "+orderSchema+".t_order WHERE user_id=3")==2,"losing orders rolled back");
        var repeat=buy(orderPort,token,3,1,winnerKey);status(repeat,200);
        check(JSON.readTree(repeat.body()).path("data").path("orderNo").asText().equals(winnerNo),"same key returns same order");
        check(quota(user,2)==2,"repeat does not acquire quota twice");
        status(buy(orderPort,token,4,1,"different_tier"),409);
        status(buy(orderPort,token,5,2,"different_session"),200);
        check(quota(user,6)==2,"different session has independent quota");
        String other=register("quotaother");
        status(buy(orderPort,other,4,2,"different_user"),200);
        check(quota(4,2)==2,"different user has independent quota");
        await(()->number("SELECT COUNT(*) FROM "+orderSchema+".t_order WHERE user_id=3 AND status='PENDING_PAYMENT'")==3,"all accepted orders reach reserved state");
        // 额度归还故障注入：减法失败时关闭事务整体回滚，库存释放事实仍可核对恢复。
        execute("CREATE TRIGGER "+orderSchema+".quota_release_fault BEFORE UPDATE ON "+orderSchema+".t_user_session_quota FOR EACH ROW BEGIN IF NEW.occupied_quantity < OLD.occupied_quantity THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='quota release fault'; END IF; END");
        execute("UPDATE "+orderSchema+".t_order SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND),next_attempt_at=CURRENT_TIMESTAMP(3) WHERE user_id=3 AND status='PENDING_PAYMENT'");
        // 库存预留的到期时间必须匹配原快照，因此同时改变测试库中的期限；不改正式库。
        execute("UPDATE "+inventorySchema+".t_stock_reservation r JOIN "+orderSchema+".t_order o ON r.order_id=o.order_no SET r.expires_at=o.expires_at WHERE o.user_id=3");
        await(()->number("SELECT COUNT(*) FROM "+orderSchema+".t_order WHERE user_id=3 AND status='CLOSING'")==3,"release failure retains nonterminal orders");
        check(quota(user,2)==2&&quota(user,6)==2,"failed release keeps all quota");
        execute("DROP TRIGGER "+orderSchema+".quota_release_fault");
        execute("UPDATE "+orderSchema+".t_order SET lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP(3) WHERE user_id=3");
        await(()->number("SELECT COUNT(*) FROM "+orderSchema+".t_order WHERE user_id=3 AND status='CLOSED' AND quota_status='RELEASED'")==3,"closure and quota recover together");
        check(quota(user,2)==0&&quota(user,6)==0,"closed orders returned quota");
        Thread.sleep(400);check(quota(user,2)==0,"duplicate recovery does not subtract again");
        var rebuy=buy(orderPort,token,3,2,"after_release");status(rebuy,200);check(quota(user,2)==2,"can buy after quota returned");
        String rollback=register("quotarollback");
        execute("CREATE TRIGGER "+orderSchema+".quota_occupy_fault BEFORE UPDATE ON "+orderSchema+".t_user_session_quota FOR EACH ROW BEGIN IF NEW.occupied_quantity > OLD.occupied_quantity THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='quota occupy fault'; END IF; END");
        status(buy(orderPort,rollback,3,1,"rollback_quota"),500);
        check(number("SELECT COUNT(*) FROM "+orderSchema+".t_order WHERE idempotency_key='rollback_quota'")==0,"occupy failure rolled back main order");
        check(number("SELECT COUNT(*) FROM "+orderSchema+".t_user_session_quota WHERE user_id=5")==0,"initial quota row also rolled back");
        execute("DROP TRIGGER "+orderSchema+".quota_occupy_fault");
        // 第一次明确无库存失败，不占用额度。
        var failed=buy(orderPort,rollback,99,1,"no_stock");status(failed,200);
        check(JSON.readTree(failed.body()).path("data").path("status").asText().equals("CREATE_FAILED"),"missing stock explicitly failed");
        check(quota(5,2)==0,"creation failure returned quota");
        // 未找到服务实例属于不确定结果，订单保留STOCK_PENDING和额度。
        int uncertain=freePort();var args=new ArrayList<>(orderArguments(port(first),false));args.add("--ticket.clients.inventory.service-id=quota-missing-instance");
        launch("ticket-order-service",uncertain,orderSchema,args);
        var pending=buy(uncertain,rollback,3,2,"uncertain_stock");status(pending,202);
        check(JSON.readTree(pending.body()).path("data").path("status").asText().equals("STOCK_PENDING"),"uncertain reserve retained pending status");
        check(quota(5,2)==2,"uncertain reserve retains quota");
        status(buy(uncertain,rollback,4,1,"uncertain_over_limit"),409);
        migration();
    }
    HttpResponse<String> buy(int port,String token,int tier,int quantity,String key)throws Exception{
        return call(port,"POST","/api/orders",Map.of("ticketTierId",tier,"quantity",quantity,"idempotencyKey",key),token);
    }
    String register(String name)throws Exception{
        status(call(port(first),"POST","/api/auth/register",Map.of("username",name,"password","Testing_123","nickname",name),null),200);
        var result=call(port(first),"POST","/api/auth/login",Map.of("username",name,"password","Testing_123"),null);status(result,200);
        return JSON.readTree(result.body()).path("data").path("accessToken").asText();
    }
    long quota(long user,long session)throws Exception{return number("SELECT occupied_quantity FROM "+orderSchema+".t_user_session_quota WHERE user_id="+user+" AND session_id="+session);}
    long number(String query)throws Exception{try(var sql=admin.createStatement();var result=sql.executeQuery(query)){return result.next()?result.getLong(1):0;}}
    void execute(String sql)throws Exception{try(var statement=admin.createStatement()){statement.execute(sql);}}
    interface Condition{boolean test()throws Exception;}
    void await(Condition condition,String message)throws Exception{
        for(int i=0;i<150;i++){if(condition.test()){check(true,message);return;}Thread.sleep(100);}throw new AssertionError(message);
    }
    /** 用V4已有订单验证V5历史回填；旧累计超过新限额仍完整保留，不清空历史。 */
    void migration()throws Exception{
        String database="ticket_order_auth_verify_"+UUID.randomUUID().toString().replace("-","");
        execute("CREATE DATABASE "+database);schemas.add(database);
        String url="jdbc:mysql://127.0.0.1:3306/"+database;
        String locations="filesystem:"+root.resolve("ticket-order-service/src/main/resources/db/migration").toString().replace('\\','/');
        Flyway.configure().dataSource(url,System.getenv("LOCAL_MYSQL_USERNAME"),System.getenv("LOCAL_MYSQL_PASSWORD")).locations(locations).target("4").load().migrate();
        int index=0;
        for(String state:List.of("PENDING_PAYMENT","COMPLETED","REVIEW_REQUIRED","CLOSED","CREATE_FAILED","REVERSED")){
            index++;String no=String.format("%032x",index);
            boolean payment=state.equals("COMPLETED")||state.equals("REVERSED");
            String evidence=payment?"'"+no+"',CURRENT_TIMESTAMP(3),'"+no+"'":"NULL,NULL,NULL";
            String reversal=state.equals("REVERSED")?"'"+no+"'":"NULL";
            execute("INSERT INTO "+database+".t_order(id,order_no,user_id,idempotency_key,total_amount,status,expires_at,next_attempt_at,payment_no,paid_at,reservation_id,reversal_no) VALUES("+index+",'"+no+"',10,'"+no+"',398,'"+state+"',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),"+evidence+","+reversal+")");
            execute("INSERT INTO "+database+".t_order_item(order_id,event_id,session_id,ticket_tier_id,ticket_tier_name,unit_price,quantity,subtotal_amount) VALUES("+index+",1,2,3,'legacy',199,2,398)");
        }
        var flyway=Flyway.configure().dataSource(url,System.getenv("LOCAL_MYSQL_USERNAME"),System.getenv("LOCAL_MYSQL_PASSWORD")).locations(locations).load();flyway.migrate();flyway.validate();
        check(number("SELECT occupied_quantity FROM "+database+".t_user_session_quota WHERE user_id=10 AND session_id=2")==6,"legacy active, sold and review orders backfilled above limit");
        check(number("SELECT COUNT(*) FROM "+database+".t_order WHERE quota_status='RELEASED'")==3,"legacy terminal failures backfilled released");
    }
}
