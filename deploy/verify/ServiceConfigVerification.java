import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LogLevel;
import org.springframework.context.ConfigurableApplicationContext;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 使用每个服务实际打包的依赖与配置文件，验证公共/服务配置和日志刷新；不加载业务Bean。 */
public class ServiceConfigVerification {
    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName="org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    static class Probe {}
    static int checks;
    static final String OWN_LOGGER="com.byy.configverification";
    static final String COMMON_LOGGER="com.byy.sharedverification";
    public static void main(String[] args) throws Exception {
        String service=args[0],suffix=UUID.randomUUID().toString().replace("-", "");
        String group="VERIFY_"+suffix,commonId="common-"+suffix+".yaml",ownId=service+"-"+suffix+".yaml";
        Properties properties=new Properties();
        properties.setProperty("serverAddr",env("NACOS_SERVER_ADDR","127.0.0.1:8848"));
        String namespace=env("NACOS_CONFIG_NAMESPACE","");
        if(!namespace.isBlank())properties.setProperty("namespace",namespace);
        for(String key:List.of("username","password")) {
            String value=env("NACOS_"+key.toUpperCase(Locale.ROOT),"");
            if(!value.isBlank())properties.setProperty(key,value);
        }
        ConfigService config=NacosFactory.createConfigService(properties);
        ConfigurableApplicationContext context=null;
        try {
            publish(config,commonId,group,common("WARN","ERROR"));
            publish(config,ownId,group,own("INFO"));
            var application=new SpringApplication(Probe.class);
            application.setDefaultProperties(Map.of("ticket.config-verification.source","local"));
            // Gateway的Netty自动配置需要真实Web环境；使用随机本机端口，避免占用用户网关。
            context=application.run("--spring.profiles.active=nacos",
                "--spring.main.web-application-type="+(service.equals("ticket-gateway")?"reactive":"none"),
                "--server.port=0","--server.address=127.0.0.1",
                "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.discovery.enabled=false",
                "--TICKET_COMMON_CONFIG_DATA_ID="+commonId,"--TICKET_SERVICE_CONFIG_DATA_ID="+ownId,
                "--NACOS_CONFIG_GROUP="+group,"--spring.main.banner-mode=off","--logging.level.root=ERROR",
                "--ticket.config-verification.command-line=command");
            check(service.equals(context.getEnvironment().getProperty("spring.application.name")),"actual module application.yml loaded");
            check("service".equals(context.getEnvironment().getProperty("ticket.config-verification.source")),"service overrides common and local");
            check("common-only".equals(context.getEnvironment().getProperty("ticket.config-verification.shared")),"inherits common property");
            check("command".equals(context.getEnvironment().getProperty("ticket.config-verification.command-line")),"command line overrides imported values");
            LoggingSystem logging=LoggingSystem.get(ServiceConfigVerification.class.getClassLoader());
            awaitLog(logging,OWN_LOGGER,LogLevel.INFO);
            awaitLog(logging,COMMON_LOGGER,LogLevel.ERROR);
            publish(config,commonId,group,common("WARN","WARN"));
            awaitLog(logging,COMMON_LOGGER,LogLevel.WARN);
            check(logging.getLoggerConfiguration(OWN_LOGGER).getEffectiveLevel()==LogLevel.INFO,"common update preserves service override");
            publish(config,ownId,group,own("DEBUG"));
            awaitLog(logging,OWN_LOGGER,LogLevel.DEBUG);
            publish(config,ownId,group,own("INFO"));
            awaitLog(logging,OWN_LOGGER,LogLevel.INFO);
            System.out.println("PASS "+service+" config: "+checks+" checks; owned configs cleaned on exit.");
        } finally {
            if(context!=null)context.close();
            try { config.removeConfig(ownId,group); config.removeConfig(commonId,group); }
            finally { config.shutDown(); }
        }
    }
    static String common(String ownLevel,String sharedLevel) {
        return "logging:\n  level:\n    "+OWN_LOGGER+": "+ownLevel+"\n    "+COMMON_LOGGER+": "+sharedLevel
            +"\nticket:\n  config-verification:\n    source: common\n    shared: common-only\n    command-line: remote\n";
    }
    static String own(String level) {
        return "logging:\n  level:\n    "+OWN_LOGGER+": "+level+"\nticket:\n  config-verification:\n    source: service\n";
    }
    static void awaitLog(LoggingSystem logging,String logger,LogLevel expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(logging.getLoggerConfiguration(logger).getEffectiveLevel()!=expected&&System.nanoTime()<deadline)Thread.sleep(100);
        check(logging.getLoggerConfiguration(logger).getEffectiveLevel()==expected,"logging system refreshed: "+logger+"="+expected);
    }
    static void publish(ConfigService config,String id,String group,String content) throws Exception {
        check(config.publishConfig(id,group,content,"yaml"),"publish config");
    }
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); checks++; }
    static String env(String key,String fallback) { String value=System.getenv(key); return value==null?fallback:value; }
}
