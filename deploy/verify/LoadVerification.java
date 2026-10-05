import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 有限并发压测：真实网关/Nacos；账号准备与结果核对不计入HTTP计时。 */
public class LoadVerification extends V1AcceptanceVerification {
    final int requests;
    final List<String> tokens=new ArrayList<>();
    final List<Map<String,Object>> report=new ArrayList<>();
    final List<String> csv=new ArrayList<>(List.of("phase,index,user,http,code,initialState,latencyMs"));
    boolean completed;
    String administrator;
    record Sample(int index,int user,int http,String code,String state,String orderNo,double ms){}
    record Batch(String name,int concurrency,long tier,List<Sample> samples,double seconds){}
    LoadVerification(Path root,int requests){super(root);this.requests=requests;}
    @Override String workName(){return "load-verification";}
    /** 保持Order默认恢复配置；只延长支付窗口，避免未支付的基准订单在计时中到期。 */
    @Override List<String> orderStartupArguments(String window){return List.of("--ticket.order.payment-window="+window);}
    int[] concurrencyLevels(){return new int[]{16,32,64,128,256};}
    boolean additionalScenarios(){return true;}
    long requestTier(long defaultTier,int index){return defaultTier;}
    String testModel(){return "closed-loop finite requests; single host; real Nacos/Gateway; 64 users; one hot tier per phase";}
    public static void main(String[] args)throws Exception{
        var test=new LoadVerification(Path.of(args[0]).toAbsolutePath(),args.length>1?Integer.parseInt(args[1]):1000);
        if(test.requests<100||test.requests>10000)throw new IllegalArgumentException("requests must be 100..10000");
        try{test.run();}finally{try{test.saveReport();}finally{test.cleanup();}}
        System.out.println("PASS load integrity; report="+test.directory.resolve("summary.json"));
    }
    @Override void run()throws Exception{
        setup("15m");
        String[] names={"ticket-auth-service","ticket-event-service","ticket-inventory-service","ticket-order-service","ticket-payment-service","ticket-gateway"};
        int[] ports={port(first),eventPort,inventoryPort,orderPort,paymentPort,gatewayPort};
        for(int i=0;i<names.length;i++){final int j=i;await(()->registered(names[j],ports[j]),"registered "+names[j]);}
        administrator=loginAs(gatewayPort,"admin").path("accessToken").asText();
        // 64个真实用户。注册和登录在计时之外执行，避免BCrypt成本混入下单指标。
        for(int i=0;i<64;i++){
            String user="load_"+i;
            status(call(gatewayPort,"POST","/api/auth/register",Map.of("username",user,"password","Testing_123","nickname",user),null),200);
            tokens.add(loginAs(gatewayPort,user).path("accessToken").asText());
        }
        System.out.println("READY real Nacos/Gateway and 64 authenticated users");
        long warm=prepare("warmup",100);reconcile(load("warmup",16,100,warm,false),100,false);
        for(int concurrency:concurrencyLevels()){
            long tier=prepare("ample_"+concurrency,requests);
            reconcile(load("ample_"+concurrency,concurrency,requests,tier,false),requests,true);
        }
        if(!additionalScenarios()){completed=true;return;}
        long tier=prepare("scarce",100);
        Batch scarce=load("scarce",256,requests,tier,false);reconcile(scarce,100,true);
        long duplicateTier=prepare("duplicate",100);
        reconcile(load("duplicate",256,requests,duplicateTier,true),100,true);
        // 对少库存抢购的全部成功订单并发付款；确认100张票最终售出，非只检查通知HTTP。
        var winners=new ArrayList<Sample>();
        try(var sql=admin.createStatement();var rows=sql.executeQuery("SELECT o.order_no,o.idempotency_key FROM "+orderSchema+".t_order o JOIN "+orderSchema+".t_order_item i ON i.order_id=o.id WHERE i.ticket_tier_id="+tier+" AND o.status='PENDING_PAYMENT'")){
            while(rows.next()){int index=Integer.parseInt(rows.getString(2).substring("scarce_".length()));winners.add(new Sample(index,index%tokens.size(),200,"OK","PENDING_PAYMENT",rows.getString(1),0));}
        }
        pay(winners,tier);
        completed=true;
    }
    long prepare(String key,int stock)throws Exception{
        var base=new HashMap<String,Object>(draft("load_"+key));
        var session=new HashMap<String,Object>();
        var now=LocalDateTime.now(ZoneOffset.ofHours(8)).withNano(0);
        session.put("name",key);session.put("venueName","压测场馆");session.put("venueAddress","本机");
        session.put("startTime",now.plusDays(1).toString());session.put("endTime",now.plusDays(1).plusHours(2).toString());
        session.put("saleStartTime",now.minusHours(1).toString());session.put("saleEndTime",now.plusHours(2).toString());session.put("purchaseLimit",100000);
        session.put("ticketTiers",List.of(Map.of("name","压测票","price",199,"totalQuantity",stock)));base.put("sessions",List.of(session));
        var response=call(gatewayPort,"POST","/api/admin/events",base,administrator);status(response,200);
        long id=data(response).path("eventId").asLong();await(()->preparation(id,administrator).equals("READY"),"stock prepared");
        status(call(gatewayPort,"POST","/api/admin/events/"+id+"/publish",Map.of(),administrator),200);return tier(data(response));
    }
    /** 固定工作线程持续取下一次请求；每次操作只有一个在途请求，不做客户端重试。 */
    Batch load(String name,int concurrency,int count,long tier,boolean duplicate)throws Exception{
        var executor=Executors.newFixedThreadPool(concurrency);
        var next=new AtomicInteger();var samples=new ConcurrentLinkedQueue<Sample>();var gate=new CountDownLatch(1);
        var workers=new ArrayList<Future<?>>();
        for(int worker=0;worker<concurrency;worker++)workers.add(executor.submit(()->{
            try{gate.await();int index;while((index=next.getAndIncrement())<count){
                int user=duplicate?0:index%tokens.size();
                String key=duplicate?name:name+"_"+index;
                samples.add(measure(index,user,"POST","/api/orders",Map.of("ticketTierId",requestTier(tier,index),"quantity",1,"idempotencyKey",key)));
            }}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        }));
        long start=System.nanoTime();gate.countDown();
        try{for(var worker:workers)worker.get(15,TimeUnit.MINUTES);}finally{executor.shutdownNow();}
        var batch=new Batch(name,concurrency,tier,List.copyOf(samples),(System.nanoTime()-start)/1e9);
        check(batch.samples().size()==count,"all request samples recorded");return batch;
    }
    Sample measure(int index,int user,String method,String path,Object body){
        long start=System.nanoTime();int status=0;String code="",state="",no="";
        try{
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+gatewayPort+path)).timeout(Duration.ofSeconds(60))
                    .header("Authorization","Bearer "+tokens.get(user)).header("Content-Type","application/json")
                    .method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());status=response.statusCode();
            var json=JSON.readTree(response.body());code=json.path("code").asText();state=json.path("data").path("status").asText();no=json.path("data").path("orderNo").asText();
        }catch(Exception ex){code=ex.getClass().getSimpleName();}
        return new Sample(index,user,status,code,state,no,(System.nanoTime()-start)/1e6);
    }
    /** 计时结束后等待异步恢复，并直接核对两个测试库的事实；200/202不等同购票成功。 */
    void reconcile(Batch batch,int stock,boolean include)throws Exception{
        long start=System.nanoTime();
        String filter=" WHERE i.ticket_tier_id="+batch.tier();
        await(()->number("SELECT COUNT(*) FROM "+orderSchema+".t_order o JOIN "+orderSchema+".t_order_item i ON i.order_id=o.id"+filter+" AND o.status NOT IN ('PENDING_PAYMENT','CREATE_FAILED')")==0,"orders settled "+batch.name());
        long winners=number("SELECT COUNT(*) FROM "+orderSchema+".t_order o JOIN "+orderSchema+".t_order_item i ON i.order_id=o.id"+filter+" AND o.status='PENDING_PAYMENT'");
        long failed=number("SELECT COUNT(*) FROM "+orderSchema+".t_order o JOIN "+orderSchema+".t_order_item i ON i.order_id=o.id"+filter+" AND o.status='CREATE_FAILED'");
        long rows=number("SELECT COUNT(*) FROM "+orderSchema+".t_order o JOIN "+orderSchema+".t_order_item i ON i.order_id=o.id"+filter);
        long reserved=number("SELECT reserved_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+batch.tier());
        long available=number("SELECT available_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+batch.tier());
        long reservationRows=number("SELECT COUNT(*) FROM "+inventorySchema+".t_stock_reservation WHERE ticket_tier_id="+batch.tier());
        check(available>=0&&reserved<=stock&&available+reserved==stock,"stock balance "+batch.name());
        check(winners==reserved&&reservationRows==winners,"orders and reservations match "+batch.name());
        if(batch.name().equals("duplicate"))check(rows==1&&winners==1,"duplicate requests create exactly one order");
        var metrics=metrics(batch);metrics.put("finalWinners",winners);metrics.put("finalRejected",failed);metrics.put("orderRows",rows);
        metrics.put("available",available);metrics.put("reserved",reserved);metrics.put("reservationRows",reservationRows);
        metrics.put("settlementWaitSeconds",(System.nanoTime()-start)/1e9);metrics.put("integrity","PASS");
        if(include){report.add(metrics);saveReport();}
        System.out.printf(Locale.ROOT,"PHASE %s c=%d rps=%.1f p95=%.1fms winners=%d rejected=%d stock=%d/%d%n",batch.name(),batch.concurrency(),batch.samples().size()/batch.seconds(),percentile(batch.samples(),.95),winners,failed,available,reserved);
    }
    Map<String,Object> metrics(Batch batch){
        var result=new LinkedHashMap<String,Object>();result.put("phase",batch.name());result.put("concurrency",batch.concurrency());result.put("requests",batch.samples().size());
        result.put("seconds",batch.seconds());result.put("httpRps",batch.samples().size()/batch.seconds());
        result.put("p50Ms",percentile(batch.samples(),.5));result.put("p95Ms",percentile(batch.samples(),.95));result.put("p99Ms",percentile(batch.samples(),.99));
        result.put("maxMs",percentile(batch.samples(),1));
        var http=new TreeMap<String,Integer>();var codes=new TreeMap<String,Integer>();var states=new TreeMap<String,Integer>();
        for(var s:batch.samples()){
            http.merge(String.valueOf(s.http()),1,Integer::sum);codes.merge(s.code(),1,Integer::sum);states.merge(s.state(),1,Integer::sum);
            csv.add(String.format(Locale.ROOT,"%s,%d,%d,%d,%s,%s,%.3f",batch.name(),s.index(),s.user(),s.http(),s.code(),s.state(),s.ms()));
        }
        result.put("httpStatus",http);result.put("responseCode",codes);result.put("initialState",states);return result;
    }
    double percentile(List<Sample> samples,double q){double[] ms=samples.stream().mapToDouble(Sample::ms).sorted().toArray();return ms.length==0?0:ms[Math.max(0,(int)Math.ceil(ms.length*q)-1)];}
    void pay(List<Sample> winners,long tier)throws Exception{
        var executor=Executors.newFixedThreadPool(32);var calls=new ArrayList<Future<Sample>>();long start=System.nanoTime();
        try{
            for(var winner:winners)calls.add(executor.submit(()->{
                var payment=call(gatewayPort,"POST","/api/orders/"+winner.orderNo()+"/payments",Map.of(),tokens.get(winner.user()));
                if(payment.statusCode()!=200)throw new AssertionError("payment creation HTTP "+payment.statusCode());
                String paymentNo=data(payment).path("paymentNo").asText();
                return measure(winner.index(),winner.user(),"POST","/api/payments/"+paymentNo+"/simulate-success",Map.of());
            }));
            var samples=new ArrayList<Sample>();for(var future:calls)samples.add(future.get(120,TimeUnit.SECONDS));
            var batch=new Batch("payment_success",32,tier,samples,(System.nanoTime()-start)/1e9);var metrics=metrics(batch);
            metrics.put("latencyOperation","simulate-success only; phase wall time also includes payment creation");
            metrics.put("paymentOperationsPerSecond",metrics.remove("httpRps"));
            metrics.put("httpRequestsIncludingCreation",winners.size()*2);
            await(()->number("SELECT COUNT(*) FROM "+orderSchema+".t_order o JOIN "+orderSchema+".t_order_item i ON i.order_id=o.id WHERE i.ticket_tier_id="+tier+" AND o.status='COMPLETED'")==winners.size(),"all paid orders completed");
            check(number("SELECT sold_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==winners.size(),"paid stock sold once");
            check(number("SELECT COUNT(*) FROM "+orderSchema+".t_ticket t JOIN "+orderSchema+".t_order_item i ON i.id=t.order_item_id WHERE i.ticket_tier_id="+tier)==winners.size(),"one ticket per paid order");
            double endToEnd=(System.nanoTime()-start)/1e9;
            metrics.put("completedOrders",winners.size());metrics.put("endToEndSeconds",endToEnd);metrics.put("completedOrdersPerSecond",winners.size()/endToEnd);metrics.put("integrity","PASS");report.add(metrics);saveReport();
            System.out.println("PAYMENT completed="+winners.size()+" tickets and sold stock match");
        }finally{executor.shutdownNow();}
    }
    void saveReport()throws Exception{
        if(directory==null)return;
        var summary=new LinkedHashMap<String,Object>();summary.put("time",Instant.now().toString());summary.put("model",testModel());
        summary.put("completed",completed);
        Path machine=root.resolve(".local/load-machine.json");if(Files.exists(machine))summary.put("machine",JSON.readTree(Files.readString(machine)));
        summary.put("requestTimeoutSeconds",60);summary.put("businessJvmMaxHeapMiB",512);summary.put("orderRecovery","defaults: delay 5s, batch 20, retry 5s..5m");summary.put("phases",report);
        Files.writeString(directory.resolve("summary.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));Files.write(directory.resolve("requests.csv"),csv);
    }
}
