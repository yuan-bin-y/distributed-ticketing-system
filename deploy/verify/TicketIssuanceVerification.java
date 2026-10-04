import com.byy.ticket.order.TicketOrderApplication;
import com.byy.ticket.order.service.OrderTicketIssueTransaction;
import com.byy.ticket.order.service.OrderStockWorkflow;
import com.sun.net.httpserver.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 真实MySQL和两个独立Spring实例验证本地出票；只创建并删除随机订单测试库。 */
public class TicketIssuanceVerification extends OrderPaymentVerification {
    ConfigurableApplicationContext first,second;
    TicketIssuanceVerification(Path root)throws Exception{super(root);}
    public static void main(String[] args)throws Exception{
        var test=new TicketIssuanceVerification(Path.of(args[0]).toAbsolutePath());
        try{test.runTickets();}finally{test.cleanup();}
        System.out.println("PASS ticket issuance: "+test.checks+" checks; isolated database and Spring instances removed.");
    }
    void runTickets()throws Exception{
        user=requireEnv("LOCAL_MYSQL_USERNAME");password=requireEnv("LOCAL_MYSQL_PASSWORD");
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",user,password);
        try(var sql=admin.createStatement()){sql.execute("CREATE DATABASE "+orderSchema);createdSchemas.add(orderSchema);}
        orderDb=DriverManager.getConnection(dbUrl(orderSchema),user,password);
        first=startContext(false,"3");
        String legacy=paid(2);first.close();first=null;
        first=startContext(true,null);port();awaitCompleted(legacy);
        check(ticketCount(legacy)==2,"V3 paid order issues on V4 upgrade");
        try(var sql=orderDb.createStatement();var rows=sql.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")){
            rows.next();check(rows.getInt(1)==4,"all four migrations recorded");
        }
        first.close();first=startContext(false,null);port();
        second=startContext(false,null);
        queryAndGuards(legacy);duplicateAndConcurrency();rollbackAndAtomicRead();staleToken();stateGuards();restartRecovery();
    }
    ConfigurableApplicationContext startContext(boolean recovery,String target){
        var args=new ArrayList<>(List.of("--server.port=0","--spring.main.banner-mode=off","--logging.level.root=ERROR",
                "--spring.datasource.url="+dbUrl(orderSchema),"--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.discovery.enabled=false","--ticket.order.dev-identity-enabled=true",
                "--ticket.order.recovery-enabled="+recovery,"--ticket.order.recovery-delay-ms=100",
                "--ticket.order.retry-base=300ms","--ticket.order.retry-max=1s"));
        if(target!=null)args.add("--spring.flyway.target="+target);
        return new SpringApplication(TicketOrderApplication.class).run(args.toArray(String[]::new));
    }
    void port(){orderPort=((WebServerApplicationContext)first).getWebServer().getPort();}
    String paid(int quantity)throws Exception{
        String no=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        try(var sql=orderDb.prepareStatement("UPDATE t_order_item SET quantity=?,subtotal_amount=199*? WHERE order_id=(SELECT id FROM t_order WHERE order_no=?)")){
            sql.setInt(1,quantity);sql.setInt(2,quantity);sql.setString(3,no);sql.executeUpdate();
        }
        try(var sql=orderDb.prepareStatement("UPDATE t_order SET status='PAID',payment_no=?,paid_at=?,total_amount=199*?,next_attempt_at=CURRENT_TIMESTAMP(3) WHERE order_no=?")){
            sql.setString(1,newNo());sql.setTimestamp(2,Timestamp.valueOf(now()));sql.setInt(3,quantity);sql.setString(4,no);sql.executeUpdate();
        }
        return no;
    }
    void queryAndGuards(String completed)throws Exception{
        var response=request("GET",orderPort,"/api/orders/"+completed+"/tickets",1L,null);
        check(response.statusCode()==200,"owner queries tickets");
        var result=data(response);check(result.path("orderStatus").asString().equals("COMPLETED"),"query includes final order state");
        var tickets=result.path("tickets");check(tickets.size()==2,"two purchased tickets returned");
        check(tickets.get(0).path("ticketIndex").asInt()==1&&tickets.get(1).path("ticketIndex").asInt()==2,"tickets sorted by index");
        for(var ticket:tickets){
            check(ticket.path("ticketNo").asString().matches("[0-9a-f]{32}"),"unique ticket number format");
            check(ticket.path("status").asString().equals("VALID"),"initial status VALID");
            check(ticket.path("eventId").asLong()==1&&ticket.path("sessionId").asLong()==1
                    &&ticket.path("ticketTierName").asString().equals("fixture"),"ticket uses purchase snapshot");
            check(!ticket.path("createdAt").isMissingNode(),"ticket creation time returned");
        }
        check(request("GET",orderPort,"/api/orders/"+completed+"/tickets",null,null).statusCode()==401,"missing identity rejected");
        check(request("GET",orderPort,"/api/orders/"+completed+"/tickets",2L,null).statusCode()==404,"foreign ticket numbers hidden");
        check(request("GET",orderPort,"/api/orders/"+newNo()+"/tickets",1L,null).statusCode()==404,"unknown order hidden");
        check(request("GET",orderPort,"/api/orders/bad/tickets",1L,null).statusCode()==400,"malformed order number rejected");
        check(request("POST",orderPort,"/api/orders/"+completed+"/tickets",1L,null).statusCode()==405,"public manual issuance not exposed");
        String unpaid=fixture("PENDING_PAYMENT",1L,true,now().plusMinutes(10));
        var pending=data(request("GET",orderPort,"/api/orders/"+unpaid+"/tickets",1L,null));
        check(pending.path("orderStatus").asString().equals("PENDING_PAYMENT")&&pending.path("tickets").isEmpty(),"unpaid owner sees state and empty ticket list");
        System.out.println("PASS ticket query, ownership, ordering and request guards");
    }
    void duplicateAndConcurrency()throws Exception{
        String no=paid(5);long id=id(no);
        List<Future<?>> tasks=new ArrayList<>();
        for(int i=0;i<20;i++){
            var context=i%2==0?first:second;
            tasks.add(workers.submit(()->{context.getBean(OrderStockWorkflow.class).advance(id);return null;}));
        }
        for(var task:tasks)task.get(15,TimeUnit.SECONDS);
        check(orderStatus(no).equals("COMPLETED")&&ticketCount(no)==5,"two instances and 20 tasks issue exactly five tickets");
        String before=ticketNumbers(no);
        first.getBean(OrderTicketIssueTransaction.class).issue(id,newNo());
        first.getBean(OrderStockWorkflow.class).advance(id);
        check(ticketNumbers(no).equals(before),"duplicate tasks preserve original ticket numbers");
        expectSqlFailure("INSERT INTO t_ticket(ticket_no,order_item_id,ticket_index) SELECT ticket_no,order_item_id,99 FROM t_ticket WHERE order_item_id="+itemId(no)+" LIMIT 1","ticket number unique constraint");
        expectSqlFailure("INSERT INTO t_ticket(ticket_no,order_item_id,ticket_index) VALUES('"+newNo()+"',"+itemId(no)+",1)","item and index unique constraint");
        expectSqlFailure("INSERT INTO t_ticket(ticket_no,order_item_id,ticket_index) VALUES('"+newNo()+"',"+itemId(no)+",0)","positive ticket index constraint");
        System.out.println("PASS multi-instance concurrent and duplicate issuance, database constraints");
    }
    void rollbackAndAtomicRead()throws Exception{
        String no=paid(102);long id=id(no),item=itemId(no);
        try(var sql=orderDb.createStatement()){
            sql.execute("CREATE TRIGGER fail_ticket BEFORE INSERT ON t_ticket FOR EACH ROW BEGIN IF NEW.order_item_id="+item
                    +" AND NEW.ticket_index=101 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected second batch failure'; END IF; END");
        }
        first.getBean(OrderStockWorkflow.class).advance(id);
        check(orderStatus(no).equals("PAID")&&ticketCount(no)==0,"failure in second batch rolls back all first-batch tickets");
        check(lastError(no)!=null,"issuance failure records durable retry");
        try(var sql=orderDb.createStatement()){
            sql.execute("DROP TRIGGER fail_ticket");
            sql.execute("CREATE TRIGGER slow_ticket BEFORE INSERT ON t_ticket FOR EACH ROW BEGIN IF NEW.order_item_id="+item
                    +" AND NEW.ticket_index=101 THEN DO SLEEP(2); END IF; END");
        }
        makeDue(no);var issuing=workers.submit(()->{first.getBean(OrderStockWorkflow.class).advance(id);return null;});
        int reads=0;
        while(!issuing.isDone()){
            var view=data(request("GET",orderPort,"/api/orders/"+no+"/tickets",1L,null));
            int count=view.path("tickets").size();String state=view.path("orderStatus").asString();
            check(state.equals("PAID")&&count==0||state.equals("COMPLETED")&&count==102,"query sees atomic order/ticket snapshot");
            reads++;Thread.sleep(100);
        }
        issuing.get(15,TimeUnit.SECONDS);check(reads>0,"query overlaps issuance transaction");
        try(var sql=orderDb.createStatement()){sql.execute("DROP TRIGGER slow_ticket");}
        check(orderStatus(no).equals("COMPLETED")&&ticketCount(no)==102,"successful retry issues all tickets across batches");
        String complete=paid(2);long completeId=id(complete);
        try(var sql=orderDb.createStatement()){
            sql.execute("CREATE TRIGGER fail_completed BEFORE UPDATE ON t_order FOR EACH ROW BEGIN IF NEW.order_no='"+complete
                    +"' AND NEW.status='COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected completion failure'; END IF; END");
        }
        first.getBean(OrderStockWorkflow.class).advance(completeId);
        check(orderStatus(complete).equals("PAID")&&ticketCount(complete)==0,"completion update failure rolls back ticket inserts");
        try(var sql=orderDb.createStatement()){sql.execute("DROP TRIGGER fail_completed");}
        makeDue(complete);first.getBean(OrderStockWorkflow.class).advance(completeId);
        check(orderStatus(complete).equals("COMPLETED")&&ticketCount(complete)==2,"completion failure retries safely");
        System.out.println("PASS whole-transaction rollback and consistent concurrent ticket reads");
    }
    void staleToken()throws Exception{
        String no=paid(2);long id=id(no);String current=newNo(),old=newNo();
        try(var sql=orderDb.prepareStatement("UPDATE t_order SET lease_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 MINUTE) WHERE order_no=?")){
            sql.setString(1,current);sql.setString(2,no);sql.executeUpdate();
        }
        first.getBean(OrderTicketIssueTransaction.class).issue(id,old);
        check(ticketCount(no)==0&&orderStatus(no).equals("PAID"),"stale token cannot issue or complete order");
        first.getBean(OrderTicketIssueTransaction.class).issue(id,current);
        check(ticketCount(no)==2&&orderStatus(no).equals("COMPLETED"),"current token completes once");
    }
    void stateGuards()throws Exception{
        for(String state:List.of("PENDING_PAYMENT","STOCK_PENDING","PAYMENT_CONFIRMING","CLOSING","CLOSED","CREATE_FAILED","REVIEW_REQUIRED","REVERSAL_PENDING","REVERSED")){
            String no=paid(2);String token=newNo();
            try(var sql=orderDb.prepareStatement("UPDATE t_order SET status=?,reversal_reason='test',reversal_no=?,lease_token=?,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY) WHERE order_no=?")){
                sql.setString(1,state);sql.setString(2,state.equals("REVERSED")?newNo():null);sql.setString(3,token);sql.setString(4,no);sql.executeUpdate();
            }
            first.getBean(OrderTicketIssueTransaction.class).issue(id(no),token);
            check(ticketCount(no)==0&&orderStatus(no).equals(state),"no issuance for state "+state);
        }
        System.out.println("PASS lease fencing and non-paid state guards");
    }
    void restartRecovery()throws Exception{
        String no=paid(3);
        first.close();first=null;second.close();second=null;
        first=startContext(true,null);port();awaitCompleted(no);
        check(ticketCount(no)==3,"background resumes durable paid order after restart");
        String numbers=ticketNumbers(no);first.close();first=startContext(true,null);port();
        check(ticketNumbers(no).equals(numbers),"completed ticket numbers persist across restart");
        System.out.println("PASS scheduled issuance and restart persistence");
    }
    void makeDue(String no)throws Exception{try(var sql=orderDb.prepareStatement("UPDATE t_order SET next_attempt_at=CURRENT_TIMESTAMP(3),lease_token=NULL,lease_until=NULL WHERE order_no=?")){sql.setString(1,no);sql.executeUpdate();}}
    long id(String no)throws Exception{try(var sql=orderDb.prepareStatement("SELECT id FROM t_order WHERE order_no=?")){sql.setString(1,no);try(var rows=sql.executeQuery()){rows.next();return rows.getLong(1);}}}
    long itemId(String no)throws Exception{try(var sql=orderDb.prepareStatement("SELECT id FROM t_order_item WHERE order_id=?")){sql.setLong(1,id(no));try(var rows=sql.executeQuery()){rows.next();return rows.getLong(1);}}}
    int ticketCount(String no)throws Exception{try(var sql=orderDb.prepareStatement("SELECT COUNT(*) FROM t_ticket WHERE order_item_id=?")){sql.setLong(1,itemId(no));try(var rows=sql.executeQuery()){rows.next();return rows.getInt(1);}}}
    String ticketNumbers(String no)throws Exception{try(var sql=orderDb.prepareStatement("SELECT GROUP_CONCAT(ticket_no ORDER BY ticket_index) FROM t_ticket WHERE order_item_id=?")){sql.setLong(1,itemId(no));try(var rows=sql.executeQuery()){rows.next();return rows.getString(1);}}}
    String lastError(String no)throws Exception{try(var sql=orderDb.prepareStatement("SELECT last_error FROM t_order WHERE order_no=?")){sql.setString(1,no);try(var rows=sql.executeQuery()){rows.next();return rows.getString(1);}}}
    void awaitCompleted(String no)throws Exception{for(int i=0;i<120;i++){if(orderStatus(no).equals("COMPLETED")){check(true,"background reaches COMPLETED");return;}Thread.sleep(100);}throw new AssertionError("issuance not completed");}
    void expectSqlFailure(String query,String name)throws Exception{try(var sql=orderDb.createStatement()){try{sql.executeUpdate(query);throw new AssertionError(name);}catch(SQLException expected){check(true,name);}}}
    @Override void cleanup()throws Exception{if(second!=null)second.close();if(first!=null)first.close();super.cleanup();}
}
