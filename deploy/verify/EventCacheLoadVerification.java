import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import org.springframework.data.redis.core.*;
import com.byy.ticket.event.cache.EventQueryCache;
import com.byy.ticket.event.cache.CacheBusyException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.sun.net.httpserver.*;

/** 两个Event进程和两个Gateway进程，真实MySQL/Redis，隔离命名空间和HTTP压测。 */
public class EventCacheLoadVerification extends EventAdministrationVerification {
    final String cachePrefix="ticket:{cache_verify_"+suffix+"}:";
    final String limitPrefix="ticket:{limit_verify_"+suffix+"}:";
    final List<Map<String,Object>> report=new ArrayList<>();
    final List<Process> eventInstances=new ArrayList<>();
    final AtomicInteger orderForwards=new AtomicInteger();
    String adminToken,buyer1,buyer2;
    int authPort,event2,gateway2;
    long eventId,tierId;
    StringRedisTemplate redis;
    EventCacheLoadVerification(Path root){super(root);}
    public static void main(String[] args)throws Exception{
        var test=new EventCacheLoadVerification(Path.of(args[0]).toAbsolutePath());
        try{test.runCache();}finally{test.cleanup();}
        System.out.println("PASS cache/rate/load: "+test.checks+" checks; isolated resources cleaned.");
    }
    void runCache()throws Exception{
        pool.shutdownNow();pool=Executors.newFixedThreadPool(32);
        admin=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/",System.getenv("LOCAL_MYSQL_USERNAME"),System.getenv("LOCAL_MYSQL_PASSWORD"));
        execute("CREATE DATABASE "+schema);created=true;
        directory=root.resolve(".local/event-cache-verification/"+suffix);Files.createDirectories(directory);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
        Files.writeString(directory.resolve("private.pem"),pem("PRIVATE KEY",keys.getPrivate().getEncoded()));
        Files.writeString(directory.resolve("public.pem"),pem("PUBLIC KEY",keys.getPublic().getEncoded()));
        credentialFile=directory.resolve("payment-order.token");Files.writeString(credentialFile,secret());
        orderCredentialFile=directory.resolve("order-payment.token");Files.writeString(orderCredentialFile,secret());
        initCredentialFile=directory.resolve("event-inventory.token");Files.writeString(initCredentialFile,secret());
        first=start(directory,"--AUTH_BOOTSTRAP_ADMIN_USERNAME=admin","--AUTH_BOOTSTRAP_ADMIN_PASSWORD=Testing_123",
            "--spring.flyway.locations=filesystem:"+root.resolve("ticket-auth-service/src/main/resources/db/migration").toString().replace('\\','/'));
        authPort=port(first);redis=first.getBean(StringRedisTemplate.class);
        adminToken=loginAs(authPort,"admin").path("accessToken").asText();
        for(String name:List.of("buyerone","buyertwo"))
            status(call(authPort,"POST","/api/auth/register",Map.of("username",name,"password","Testing_123","nickname",name),null),200);
        buyer1=loginAs(authPort,"buyerone").path("accessToken").asText();
        buyer2=loginAs(authPort,"buyertwo").path("accessToken").asText();
        eventSchema="ticket_event_auth_verify_"+suffix;execute("CREATE DATABASE "+eventSchema);schemas.add(eventSchema);
        eventPort=freePort();event2=freePort();
        events(false);
        var created=createDraft("cache_fixture",adminToken);eventId=created.path("eventId").asLong();tierId=tier(created);
        // 查询优化夹具：只准备发布规则，不伪装成已验证库存初始化。
        execute("UPDATE "+eventSchema+".t_ticket_tier SET preparation_status='READY'");
        status(call(eventPort,"POST","/api/admin/events/"+eventId+"/publish",Map.of(),adminToken),200);
        batch("warmup-baseline",100,false,false);
        batch("uncached",2000,true,false);
        stopEvents();events(true);
        cacheCorrectness();
        clear(cachePrefix);
        batch("cold-hotspot",64,true,false);
        batch("cached",2000,true,false);
        negativeCache();publication();boundaries();
        gatewayPort=gatewayInstance(false,false);gateway2=gatewayInstance(false,false);
        // 边界验证及网关启动可能超过缓存TTL，先重新预热再测稳定命中。
        clear(limitPrefix);batch("warmup-gateway",100,false,true);batch("gateway-cached",2000,true,true);
        stopGateways();
        gatewayPort=gatewayInstance(true,false);gateway2=gatewayInstance(true,false);
        clear(limitPrefix);batch("gateway-limited",2000,true,true);
        identityAndGlobalLimit();
        int unavailable=gatewayInstance(true,true);
        status(call(unavailable,"GET","/api/events/"+eventId,null,null),503);
        check(JSON.readTree(call(unavailable,"GET","/api/events/"+eventId,null,null).body()).path("code").asText().equals("SERVICE_BUSY"),
            "limiter Redis outage fails closed");
        Files.writeString(directory.resolve("report.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("REPORT "+directory.resolve("report.json"));
    }
    void events(boolean cache)throws Exception{
        for(int port:List.of(eventPort,event2)){
            var args=new ArrayList<>(eventArguments(authPort,false));
            args.addAll(List.of("--ticket.event-cache.enabled="+cache,"--ticket.event-cache.prefix="+cachePrefix,
                "--ticket.event-cache.metrics-enabled=true","--logging.level.com.byy.ticket.event.cache=INFO",
                "--ticket.event-cache.database-concurrency=64"));
            launch("ticket-event-service",port,eventSchema,args);eventInstances.add(children.get(children.size()-1));
        }
    }
    void stopEvents()throws Exception{for(var process:eventInstances)stopOwned(process);eventInstances.clear();}
    void stopOwned(Process process)throws Exception{process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();}}
    void stopGateways()throws Exception{for(var process:gatewayProcesses)stopOwned(process);gatewayProcesses.clear();}
    final List<Process> gatewayProcesses=new ArrayList<>();
    HttpServer orders;
    int gatewayInstance(boolean limited,boolean badRedis)throws Exception{
        if(orders==null){
            orders=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);orders.setExecutor(eventWorkers);
            orders.createContext("/",e->{orderForwards.incrementAndGet();send(e,200,"{\"code\":\"OK\",\"data\":{}}");e.close();});orders.start();
        }
        int port=freePort();
        var args=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-jar",
            root.resolve("ticket-gateway/target/ticket-gateway-1.0-SNAPSHOT.jar").toString(),"--server.port="+port,
            "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.discovery.enabled=false",
            "--ticket.security.jwk-set-uri=http://127.0.0.1:"+authPort+"/.well-known/jwks.json",
            "--ticket.security.redis-prefix="+prefix,"--logging.level.root=ERROR",
            "--ticket.rate-limit.enabled="+limited,"--ticket.rate-limit.prefix="+limitPrefix,
            "--ticket.rate-limit.event-rate=10000","--ticket.rate-limit.event-burst=10000",
            "--ticket.rate-limit.event-client-rate=1","--ticket.rate-limit.event-client-burst=4",
            "--ticket.rate-limit.order-rate=1","--ticket.rate-limit.order-burst=3",
            "--ticket.rate-limit.order-client-rate=1","--ticket.rate-limit.order-client-burst=2",
            "--spring.cloud.gateway.server.webflux.routes[0].id=cache-events",
            "--spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:"+eventPort,
            "--spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/events/**",
            "--spring.cloud.gateway.server.webflux.routes[1].id=cache-orders",
            "--spring.cloud.gateway.server.webflux.routes[1].uri=http://127.0.0.1:"+orders.getAddress().getPort(),
            "--spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/api/orders/**"));
        if(badRedis)args.add("--spring.data.redis.port="+freePort());
        var process=new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true)
            .redirectOutput(directory.resolve("gateway-"+port+".log").toFile()).start();
        children.add(process);gatewayProcesses.add(process);
        for(int i=0;i<160;i++){if(!process.isAlive())throw new AssertionError("Gateway exited: "+directory);
            try{if(call(port,"GET","/api/events/ping",null,null).statusCode()==200)return port;}catch(Exception ignored){}Thread.sleep(150);}
        throw new AssertionError("Gateway startup timeout");
    }
    record Sample(int status,double millis){}
    void batch(String name,int count,boolean record,boolean gateway)throws Exception{
        Thread.sleep(650);long before=selects();var start=new CountDownLatch(1);var futures=new ArrayList<Future<Sample>>();
        long started=System.nanoTime();
        for(int i=0;i<count;i++){int destination=i%2==0?(gateway?gatewayPort:eventPort):(gateway?gateway2:event2);
            futures.add(pool.submit(()->{start.await();long t=System.nanoTime();var response=call(destination,"GET","/api/events/"+eventId,null,null);
                if(response.statusCode()==200 && !data(response).path("id").asText().equals(Long.toString(eventId)))
                    throw new AssertionError("wrong cache payload");
                return new Sample(response.statusCode(),(System.nanoTime()-t)/1e6);}));
        }
        start.countDown();List<Sample> samples=new ArrayList<>();for(var future:futures)samples.add(future.get(30,TimeUnit.SECONDS));
        double seconds=(System.nanoTime()-started)/1e9;Thread.sleep(650);long queries=selects()-before;
        long success=samples.stream().filter(s->s.status==200).count(),limited=samples.stream().filter(s->s.status==429).count();
        double[] latency=samples.stream().mapToDouble(Sample::millis).sorted().toArray();
        check(success+limited==count,"batch has only expected responses: "+name+" "+
            samples.stream().collect(java.util.stream.Collectors.groupingBy(Sample::status,java.util.stream.Collectors.counting())));
        // performance_schema汇总计数用于压力对照，不能假定并发采样是精确审计。
        if(name.equals("uncached"))check(queries>=count*0.98&&queries<=count+2,
            "uncached business SELECT count tracks requests; observed="+queries+", requests="+count);
        if(name.equals("cold-hotspot"))check(queries<=2&&queries>=1,"cross-process cold rebuild needs at most two SELECTs");
        if(name.equals("cached")||name.equals("gateway-cached"))check(queries==0,"warm cache has zero database SELECTs");
        if(name.equals("gateway-limited")){check(limited>count*0.9,"burst limited before business routing");check(success<=5+Math.ceil(seconds),"two gateways share one IP bucket");}
        Map<String,Object> row=new LinkedHashMap<>();row.put("phase",name);row.put("requests",count);row.put("concurrency",32);
        row.put("seconds",seconds);row.put("requestRps",count/seconds);row.put("success",success);row.put("limited",limited);
        row.put("selects",queries);row.put("p50Ms",latency[(int)(count*.5)]);row.put("p95Ms",latency[(int)(count*.95)]);row.put("p99Ms",latency[(int)(count*.99)]);
        if(record)report.add(row);
        System.out.println("PASS "+JSON.writeValueAsString(row));
    }
    long selects()throws Exception{
        return number("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest "
            +"WHERE SCHEMA_NAME='"+eventSchema+"' AND DIGEST_TEXT LIKE 'SELECT%' "
            +"AND (DIGEST_TEXT LIKE '%FROM `t_event%' OR DIGEST_TEXT LIKE '%FROM `t_ticket_tier%')");
    }
    void cacheCorrectness()throws Exception{
        for(String path:List.of("/api/events/"+eventId,"/api/events/"+eventId+"/sessions","/api/events?page=1&pageSize=20")){
            var a=call(eventPort,"GET",path,null,null);var b=call(event2,"GET",path,null,null);
            status(a,200);status(b,200);check(data(a).equals(data(b)),"VO cache roundtrip across two processes");
            check(!a.headers().firstValue("X-Trace-Id").equals(b.headers().firstValue("X-Trace-Id")),"cached data has fresh request trace");
        }
        status(call(eventPort,"GET","/api/events/0",null,null),400);
        status(call(eventPort,"GET","/api/events?page=0",null,null),400);
        // 内部购票规则不缓存展示中的价格；直接SQL修改仅为隔离夹具。
        execute("UPDATE "+eventSchema+".t_ticket_tier SET price=201 WHERE id="+tierId);
        var rule=headers(eventPort,"GET","/internal/ticket-tiers/"+tierId+"/purchase-rule",null,
            Map.of("X-Order-Event-Credential",outboundCredential("EVENT")));
        status(rule,200);check(data(rule).path("price").decimalValue().intValue()==201,"purchase rule reads authoritative price");
    }
    void negativeCache()throws Exception{
        Thread.sleep(650);long before=selects();
        for(int i=0;i<12;i++)status(call(i%2==0?eventPort:event2,"GET","/api/events/999999",null,null),404);
        Thread.sleep(650);check(selects()-before==1,"missing event uses short negative cache");
    }
    void publication()throws Exception{
        var draft=createDraft("publish_cached_missing",adminToken);long id=draft.path("eventId").asLong();
        status(call(event2,"GET","/api/events/"+id,null,null),404);call(event2,"GET","/api/events?page=1",null,null);
        String previous=redis.opsForValue().get(cachePrefix+"epoch");
        status(call(eventPort,"POST","/api/admin/events/"+id+"/publish",Map.of(),adminToken),409);
        check(Objects.equals(previous,redis.opsForValue().get(cachePrefix+"epoch")),"failed publish does not invalidate");
        execute("UPDATE "+eventSchema+".t_ticket_tier SET preparation_status='READY'");
        status(call(eventPort,"POST","/api/admin/events/"+id+"/publish",Map.of(),adminToken),200);
        check(!Objects.equals(previous,redis.opsForValue().get(cachePrefix+"epoch")),"commit rotates cache epoch");
        status(call(event2,"GET","/api/events/"+id,null,null),200);
        check(data(call(event2,"GET","/api/events?page=1",null,null)).path("total").asInt()==2,"publish invalidates list and negative cache across processes");
    }
    void boundaries()throws Exception{
        String p=cachePrefix+"boundary:";
        redis.opsForValue().set(p+"epoch","0");
        var cache=new EventQueryCache(redis,JSON,true,p,1000,400,100,2);
        var type=JSON.getTypeFactory().constructType(String.class);
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var old=pool.submit(()->cache.get("lease",type,()->{entered.countDown();try{resume.await();}catch(InterruptedException e){throw new RuntimeException(e);}return "old";}));
        check(entered.await(2,TimeUnit.SECONDS),"old owner starts rebuild");Thread.sleep(180);
        check(cache.get("lease",type,()->"new").equals("new"),"expired lease allows new rebuild");
        redis.opsForValue().set(p+"0:lease:lock","other-owner",Duration.ofSeconds(2));
        resume.countDown();check(old.get(3,TimeUnit.SECONDS).equals("old"),"old request may finish with its own snapshot");
        check(cache.get("lease",type,()->"bad").equals("new"),"expired owner cannot overwrite new cache");
        check("other-owner".equals(redis.opsForValue().get(p+"0:lease:lock")),"expired owner cannot release another owner's lock");
        redis.opsForValue().set(p+"0:blocked:lock","held",Duration.ofSeconds(2));
        try{cache.get("blocked",type,()->"bad");throw new AssertionError("wait should be bounded");}
        catch(CacheBusyException expected){check(true,"cache rebuild wait has a deadline");}
        var tx=new TransactionTemplate(first.getBean(PlatformTransactionManager.class));
        String before=redis.opsForValue().get(p+"epoch");
        tx.execute(status->{cache.invalidateAfterCommit();status.setRollbackOnly();return null;});
        check(Objects.equals(before,redis.opsForValue().get(p+"epoch")),"rollback keeps cache generation");
        tx.execute(status->{cache.invalidateAfterCommit();return null;});
        check(!Objects.equals(before,redis.opsForValue().get(p+"epoch")),"transaction commit changes generation");
        String epoch=redis.opsForValue().get(p+"epoch");
        redis.opsForValue().set(p+epoch+":corrupt","{bad",Duration.ofSeconds(2));
        check(cache.get("corrupt",type,()->"repaired").equals("repaired"),"malformed cache is rebuilt");
        redis.delete(p+"epoch");
        check(cache.get("corrupt",type,()->"new-generation").equals("new-generation"),
            "missing epoch cannot resurrect old-generation cache");
        var bounded=new EventQueryCache(redis,JSON,false,p+"bulk:",1000,400,100,1);
        var holding=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pending=pool.submit(()->bounded.uncached(()->{holding.countDown();try{release.await();}
            catch(InterruptedException e){throw new RuntimeException(e);}return "done";}));
        try{
            check(holding.await(2,TimeUnit.SECONDS),"database fallback permit occupied");
            try{bounded.uncached(()->"bad");throw new AssertionError("fallback should be bounded");}
            catch(CacheBusyException expected){check(true,"fallback concurrency limit rejects excess work");}
        }finally{release.countDown();}
        check(pending.get(2,TimeUnit.SECONDS).equals("done"),"fallback permit is safely released");
        var factory=new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
            new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",freePort()),
            org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(100)).shutdownTimeout(Duration.ZERO).build());
        factory.afterPropertiesSet();factory.start();
        try{
            var unavailable=new StringRedisTemplate(factory);
            var fallback=new EventQueryCache(unavailable,JSON,true,p+"outage:",1000,400,100,1);
            check(fallback.get("read",type,()->"database").equals("database")&&fallback.failures.sum()>0,
                "Redis outage uses controlled database fallback");
        }finally{factory.destroy();}
    }
    void identityAndGlobalLimit()throws Exception{
        clear(limitPrefix);
        for(int i=0;i<4;i++)status(call(i%2==0?gatewayPort:gateway2,"GET","/api/events/"+eventId,null,null),200);
        var denied=headers(gatewayPort,"GET","/api/events/"+eventId,null,Map.of("X-Forwarded-For","203.0.113.9","X-User-Id","999"));
        status(denied,429);check(denied.headers().firstValue("Retry-After").isPresent(),"429 provides retry guidance");
        check(JSON.readTree(denied.body()).path("traceId").asText().equals(denied.headers().firstValue("X-Trace-Id").orElse("")),
            "429 keeps response trace");
        status(call(gatewayPort,"GET","/api/events/"+eventId,null,buyer1),200);
        status(call(gateway2,"GET","/api/events/"+eventId,null,buyer2),200);
        clear(limitPrefix);int before=orderForwards.get();
        status(call(gatewayPort,"GET","/api/orders/probe",null,buyer1),200);
        status(call(gateway2,"GET","/api/orders/probe",null,buyer1),200);
        status(call(gatewayPort,"GET","/api/orders/probe",null,buyer1),429);
        status(call(gateway2,"GET","/api/orders/probe",null,buyer2),200);
        status(call(gatewayPort,"GET","/api/orders/probe",null,buyer2),429);
        check(orderForwards.get()-before==3,"global bucket limits aggregate authenticated users before routing");
        status(call(gatewayPort,"GET","/api/orders/probe",null,null),401);
    }
    void clear(String namespace){
        try(var cursor=redis.scan(ScanOptions.scanOptions().match(namespace+"*").count(100).build())){
            var keys=new ArrayList<String>();cursor.forEachRemaining(keys::add);if(!keys.isEmpty())redis.delete(keys);
        }
    }
    @Override void cleanup()throws Exception{
        if(orders!=null)orders.stop(0);
        try{if(redis!=null){clear(cachePrefix);clear(limitPrefix);}}finally{super.cleanup();}
    }
}

