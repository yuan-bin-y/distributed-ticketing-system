import com.rabbitmq.client.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 默认只查看一条死信并放回；显式--replay才在发布确认后删除原死信。 */
public class PaymentDeadLetterReplay {
    public static void main(String[] args)throws Exception {
        String prefix=env("TICKET_MQ_PREFIX","ticket");
        boolean replay=Arrays.asList(args).contains("--replay");
        if(!prefix.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("队列前缀不合法");
        var factory=new ConnectionFactory();
        factory.setHost(env("LOCAL_RABBITMQ_HOST","127.0.0.1"));
        factory.setPort(Integer.parseInt(env("LOCAL_RABBITMQ_PORT","5672")));
        factory.setUsername(env("LOCAL_RABBITMQ_USERNAME","guest"));
        factory.setPassword(env("LOCAL_RABBITMQ_PASSWORD","guest"));
        factory.setVirtualHost(env("LOCAL_RABBITMQ_VHOST","/"));
        try(var connection=factory.newConnection();var channel=connection.createChannel()){
            var delivery=channel.basicGet(prefix+".order.payment-dead",false);
            if(delivery==null){System.out.println("Dead queue is empty.");return;}
            long tag=delivery.getEnvelope().getDeliveryTag();
            System.out.println("eventId="+delivery.getProps().getMessageId());
            System.out.println(new String(delivery.getBody(),StandardCharsets.UTF_8));
            if(!replay){channel.basicNack(tag,false,true);System.out.println("Preview only; message retained.");return;}
            channel.confirmSelect();
            var returned=new AtomicBoolean();
            channel.addReturnListener((ReturnListener)(code,text,exchange,key,properties,body)->returned.set(true));
            var headers=new HashMap<String,Object>();
            if(delivery.getProps().getHeaders()!=null){
                Object trace=delivery.getProps().getHeaders().get("X-Trace-Id");
                if(trace!=null)headers.put("X-Trace-Id",trace);
                for(String key:List.of("traceparent","tracestate")) {
                    Object context=delivery.getProps().getHeaders().get(key);
                    // 原生 RabbitMQ 客户端收到的是 LongString，不能只识别 Java String。
                    if(context instanceof LongString value && value.length()<=512)
                        context=new String(value.getBytes(),StandardCharsets.UTF_8);
                    if(context instanceof String value && value.length()<=512) headers.put(key,value);
                }
            }
            headers.put("x-ticket-retry",0);
            headers.put("x-ticket-redrive",true);
            var properties=new AMQP.BasicProperties.Builder().contentType("application/json")
                    .contentEncoding("UTF-8").deliveryMode(2).messageId(delivery.getProps().getMessageId())
                    .headers(headers).build();
            channel.basicPublish(prefix+".payment.exchange","payment.succeeded",true,properties,delivery.getBody());
            channel.waitForConfirmsOrDie(5000);
            if(returned.get())throw new IllegalStateException("消息无法路由；原死信保留");
            channel.basicAck(tag,false);
            System.out.println("Replay confirmed; original event identity preserved.");
        }
    }
    static String env(String key,String fallback){String value=System.getenv(key);return value==null||value.isBlank()?fallback:value;}
}
