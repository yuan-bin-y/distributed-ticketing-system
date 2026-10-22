import com.rabbitmq.client.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 在HA网络内验证真实quorum队列；只操作本次随机探针队列。 */
public class HaRabbitProbe {
    public static void main(String[] args)throws Exception {
        if(args.length!=3 || !args[1].matches("ticket-ha-probe-[a-f0-9]{32}") || !args[2].matches("[a-f0-9]{32}"))throw new IllegalArgumentException("探针参数无效");
        var factory=new ConnectionFactory();factory.setUsername("ticket");
        factory.setPassword(Files.readString(Path.of("/run/secrets/rabbit-password")).trim());
        factory.setConnectionTimeout(3000);factory.setAutomaticRecoveryEnabled(false);
        var addresses=args[0].equals("prepare")?List.of(new Address("rabbit1",5672)):List.of(new Address("rabbit2",5672),new Address("rabbit3",5672));
        try(var connection=factory.newConnection(addresses);var channel=connection.createChannel()) {
            channel.confirmSelect();
            if(args[0].equals("prepare")) {
                channel.queueDeclare(args[1],true,false,false,Map.of("x-queue-type","quorum","x-quorum-initial-group-size",3,"x-queue-leader-locator","client-local"));
                channel.basicPublish("",args[1],true,MessageProperties.PERSISTENT_TEXT_PLAIN,args[2].getBytes(StandardCharsets.UTF_8));
                channel.waitForConfirmsOrDie(5000);System.out.println("quorum probe published and confirmed");
            } else if(args[0].equals("verify")) {
                var original=channel.basicGet(args[1],false);
                if(original==null || !args[2].equals(new String(original.getBody(),StandardCharsets.UTF_8)))throw new IllegalStateException("原持久消息缺失");
                channel.basicAck(original.getEnvelope().getDeliveryTag(),false);
                channel.basicPublish("",args[1],true,MessageProperties.PERSISTENT_TEXT_PLAIN,(args[2]+"-after").getBytes(StandardCharsets.UTF_8));
                channel.waitForConfirmsOrDie(5000);
                var after=channel.basicGet(args[1],false);
                if(after==null || !(args[2]+"-after").equals(new String(after.getBody(),StandardCharsets.UTF_8)))throw new IllegalStateException("切换后发布/消费失败");
                channel.basicAck(after.getEnvelope().getDeliveryTag(),false);channel.queueDelete(args[1]);
                System.out.println("quorum leader failover retained message and accepts new confirmed publications");
            } else throw new IllegalArgumentException("探针模式无效");
        }
    }
}
