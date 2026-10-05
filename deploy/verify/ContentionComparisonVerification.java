import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 固定256并发，仅改变Inventory连接池大小或同场次票档数，带末尾基准复测。 */
public class ContentionComparisonVerification extends ProfilingLoadVerification {
    List<Long> activeTiers=List.of();
    int inventoryPool=10;
    int inventoryRestart;
    ContentionComparisonVerification(Path root,int requests){super(root,requests);}
    @Override String workName(){return "contention-comparison";}
    @Override String testModel(){return "closed-loop; 256 concurrency; 64 users; Inventory pool10/hot1 vs pool30/hot1 vs pool10/10 tiers in ONE session; baseline repeat";}
    @Override long requestTier(long defaultTier,int index){return activeTiers.isEmpty()?defaultTier:activeTiers.get(index%activeTiers.size());}
    @Override void launch(String module,int port,String database,List<String> extra)throws Exception{
        var args=new ArrayList<>(extra);
        if(module.contains("inventory")){args.add("--spring.datasource.hikari.maximum-pool-size="+inventoryPool);args.add("--spring.datasource.hikari.minimum-idle="+inventoryPool);}
        super.launch(module,port,database,args);
    }
    @Override void run()throws Exception{
        setup("15m");
        String[] names={"ticket-auth-service","ticket-event-service","ticket-inventory-service","ticket-order-service","ticket-payment-service","ticket-gateway"};
        int[] ports={port(first),eventPort,inventoryPort,orderPort,paymentPort,gatewayPort};
        for(int i=0;i<names.length;i++){final int j=i;await(()->registered(names[j],ports[j]),"registered "+names[j]);}
        administrator=loginAs(gatewayPort,"admin").path("accessToken").asText();
        for(int i=0;i<64;i++){
            String user="load_"+i;status(call(gatewayPort,"POST","/api/auth/register",Map.of("username",user,"password","Testing_123","nickname",user),null),200);
            tokens.add(loginAs(gatewayPort,user).path("accessToken").asText());
        }
        warm("warm_A");phase("A_pool10_hot1",1);
        restartInventory(30);warm("warm_B");phase("B_pool30_hot1",1);
        restartInventory(10);warm("warm_C");phase("C_pool10_tiers10",10);
        // 再测一次A，避免把JIT或运行顺序变化误判为分散热点的效果。
        phase("A2_pool10_hot1",1);
        completed=true;
    }
    void restartInventory(int pool)throws Exception{
        // 原启动器使用同端口日志文件；重启前归档，保留前一组完整计时证据。
        Files.copy(directory.resolve("ticket-inventory-service-"+inventoryPort+".log"),directory.resolve("inventory-pool"+inventoryPool+"-restart"+(++inventoryRestart)+".log"));
        stockProcess.destroy();if(!stockProcess.waitFor(5,TimeUnit.SECONDS)){stockProcess.destroyForcibly();stockProcess.waitFor(5,TimeUnit.SECONDS);}
        inventoryPool=pool;launch("ticket-inventory-service",inventoryPort,inventorySchema,List.of());stockProcess=children.get(children.size()-1);
        await(()->registered("ticket-inventory-service",inventoryPort),"restarted Inventory registered");
    }
    void warm(String name)throws Exception{
        activeTiers=List.of();long tier=prepare(name,100);
        reconcile(load(name,32,100,tier,false),100,false);
    }
    List<Long> prepareTiers(String name,int count)throws Exception{
        var base=new HashMap<String,Object>(draft("compare_"+name));
        // 一个Event、同一个Session，保留相同用户/场次限购竞争；只分散库存行。
        var original=(Map<?,?>)((List<?>)base.get("sessions")).get(0);
        var session=new HashMap<String,Object>();original.forEach((key,value)->session.put(key.toString(),value));session.put("purchaseLimit",100000);
        var tiers=new ArrayList<Map<String,Object>>();
        for(int i=0;i<count;i++)tiers.add(Map.of("name","票档_"+i,"price",199,"totalQuantity",requests/count));
        session.put("ticketTiers",tiers);base.put("sessions",List.of(session));
        var created=call(gatewayPort,"POST","/api/admin/events",base,administrator);status(created,200);
        long eventId=data(created).path("eventId").asLong();
        await(()->number("SELECT COUNT(*) FROM "+eventSchema+".t_ticket_tier WHERE session_id="+session(data(created))+" AND preparation_status='READY'")==count,"all compared tiers ready");
        status(call(gatewayPort,"POST","/api/admin/events/"+eventId+"/publish",Map.of(),administrator),200);
        var result=new ArrayList<Long>();for(var tier:data(created).path("sessions").get(0).path("ticketTiers"))result.add(tier.path("ticketTierId").asLong());return result;
    }
    void phase(String name,int tierCount)throws Exception{
        activeTiers=prepareTiers(name,tierCount);
        Batch batch=load(name,256,requests,activeTiers.get(0),false);
        String ids=activeTiers.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
        String filter=" WHERE i.ticket_tier_id IN ("+ids+")";
        await(()->number("SELECT COUNT(*) FROM "+orderSchema+".t_order o JOIN "+orderSchema+".t_order_item i ON i.order_id=o.id"+filter+" AND o.status NOT IN ('PENDING_PAYMENT','CREATE_FAILED')")==0,"comparison orders settled");
        long winners=number("SELECT COUNT(*) FROM "+orderSchema+".t_order o JOIN "+orderSchema+".t_order_item i ON i.order_id=o.id"+filter+" AND o.status='PENDING_PAYMENT'");
        check(winners==requests,"all comparison requests reserved");
        for(long tier:activeTiers){
            check(number("SELECT reserved_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==requests/tierCount,"each tier reserved expected count");
            check(number("SELECT available_quantity+sold_quantity FROM "+inventorySchema+".t_ticket_stock WHERE ticket_tier_id="+tier)==0,"each tier balance");
            check(number("SELECT COUNT(*) FROM "+inventorySchema+".t_stock_reservation WHERE ticket_tier_id="+tier)==requests/tierCount,"reservation rows match");
        }
        var metrics=metrics(batch);metrics.put("inventoryPoolSize",inventoryPool);metrics.put("tierCount",tierCount);metrics.put("finalWinners",winners);metrics.put("integrity","PASS");report.add(metrics);saveReport();
        System.out.printf(Locale.ROOT,"COMPARE %s rps=%.1f p95=%.1fms winners=%d%n",name,requests/batch.seconds(),percentile(batch.samples(),.95),winners);
    }
    public static void main(String[] args)throws Exception{
        var test=new ContentionComparisonVerification(Path.of(args[0]).toAbsolutePath(),Integer.parseInt(args[1]));
        if(test.requests<100||test.requests>10000||test.requests%10!=0)throw new IllegalArgumentException("requests must be 100..10000 and divisible by 10");
        try{test.run();}finally{try{test.saveReport();}finally{test.cleanup();}}
        System.out.println("PASS comparison; report="+test.directory.resolve("summary.json"));
    }
}
