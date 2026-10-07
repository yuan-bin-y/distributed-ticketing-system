import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.byy.ticket.event.cache.EventCacheTtl;
import com.byy.ticket.event.cache.EventQueryCache;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 真Nacos发布与监听、真Redis TTL；唯一配置和Key，结束只删除自有资源。 */
public class EventConfigVerification {
    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName="org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({EventCacheTtl.class, EventQueryCache.class})
    static class Probe {}
    static int checks;
    static void check(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-", "");
        String dataId="verify-event-"+suffix+".yaml", commonId="verify-common-"+suffix+".yaml", group="VERIFY_"+suffix;
        String prefix="ticket:{config_verify_"+suffix+"}:";
        Properties properties=new Properties();
        properties.setProperty("serverAddr",env("NACOS_SERVER_ADDR","127.0.0.1:8848"));
        String namespace=env("NACOS_CONFIG_NAMESPACE","");
        if(!namespace.isBlank())properties.setProperty("namespace",namespace);
        for(String name:List.of("username","password")) {
            String value=System.getenv("NACOS_"+name.toUpperCase(Locale.ROOT));
            if(value!=null&&!value.isBlank())properties.setProperty(name,value);
        }
        ConfigService config=NacosFactory.createConfigService(properties);
        ConfigurableApplicationContext context=null;
        StringRedisTemplate redis=null;
        try {
            check(config.publishConfig(commonId,group,"logging:\n  level:\n    com.byy: INFO\n","yaml"),"publish isolated common config");
            publish(config,dataId,group,"1000");
            context=SpringApplication.run(Probe.class,
                "--spring.main.web-application-type=none", "--spring.profiles.active=nacos",
                "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.discovery.enabled=false",
                "--EVENT_CONFIG_DATA_ID="+dataId,"--EVENT_CONFIG_GROUP="+group,
                "--TICKET_SERVICE_CONFIG_DATA_ID="+dataId,"--NACOS_CONFIG_GROUP="+group,
                "--TICKET_COMMON_CONFIG_DATA_ID="+commonId,
                "--ticket.event-cache.prefix="+prefix,"--logging.level.root=ERROR",
                "--spring.data.redis.host="+env("LOCAL_REDIS_HOST","127.0.0.1"),
                "--spring.data.redis.port="+env("LOCAL_REDIS_PORT","6379"),
                "--spring.data.redis.password="+env("LOCAL_REDIS_PASSWORD",""));
            var ttl=context.getBean(EventCacheTtl.class);
            var cache=context.getBean(EventQueryCache.class);
            redis=context.getBean(StringRedisTemplate.class);
            var type=context.getBean(ObjectMapper.class).getTypeFactory().constructType(String.class);
            check(ttl.millis()==1000,"loads Nacos YAML instead of local 60000");
            cache.get("first",type,()->"one");
            String epoch=redis.opsForValue().get(prefix+"epoch");
            long first=redis.getExpire(prefix+epoch+":first",TimeUnit.MILLISECONDS);
            check(first>0&&first<=1200,"initial Redis TTL includes existing jitter");
            publish(config,dataId,group,"4000");
            await(ttl,4000);
            check(context.getBean(EventQueryCache.class)==cache,"cache object not recreated");
            long existing=redis.getExpire(prefix+epoch+":first",TimeUnit.MILLISECONDS);
            check(existing<=first,"old cache TTL not extended");
            cache.get("second",type,()->"two");
            long second=redis.getExpire(prefix+epoch+":second",TimeUnit.MILLISECONDS);
            check(second>3000&&second<=4800,"new Redis writes use refreshed TTL");
            for(String bad:List.of("-1","bad","86400001")) {
                publish(config,dataId,group,bad);
                awaitEnvironment(context,bad);
                Thread.sleep(300);
                check(ttl.millis()==4000,"bad TTL keeps last valid value: "+bad);
            }
            publish(config,dataId,group,"1000");
            await(ttl,1000);
            cache.get("rollback",type,()->"three");
            long rollback=redis.getExpire(prefix+epoch+":rollback",TimeUnit.MILLISECONDS);
            check(rollback>0&&rollback<=1200,"rollback applies without restart");
            System.out.println("PASS Nacos dynamic cache TTL: "+checks+" checks; owned resources cleaned on exit.");
        } finally {
            try {
                if(redis!=null) { var keys=redis.keys(prefix+"*"); if(keys!=null&&!keys.isEmpty())redis.delete(keys); }
            } finally {
                if(context!=null)context.close();
                try { config.removeConfig(dataId,group); config.removeConfig(commonId,group); } finally { config.shutDown(); }
            }
        }
    }
    static void publish(ConfigService config,String dataId,String group,String value) throws Exception {
        check(config.publishConfig(dataId,group,"ticket:\n  event-cache:\n    ttl-ms: "+value+"\n","yaml"),"publish config");
    }
    static void await(EventCacheTtl ttl,long expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(ttl.millis()!=expected&&System.nanoTime()<deadline)Thread.sleep(100);
        check(ttl.millis()==expected,"runtime TTL refreshed to "+expected);
    }
    static void awaitEnvironment(ConfigurableApplicationContext context,String expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!expected.equals(context.getEnvironment().getProperty(EventCacheTtl.KEY))&&System.nanoTime()<deadline)Thread.sleep(100);
        check(expected.equals(context.getEnvironment().getProperty(EventCacheTtl.KEY)),"Nacos refresh reached environment");
    }
    static String env(String key,String fallback) { String value=System.getenv(key); return value==null?fallback:value; }
}
