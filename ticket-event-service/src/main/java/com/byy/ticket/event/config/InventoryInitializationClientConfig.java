package com.byy.ticket.event.config;
import com.byy.ticket.security.service.*;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
/** Event通过服务名定位Inventory；不依赖库存实体、Mapper或数据库。 */
@Configuration
@EnableConfigurationProperties({EventPreparationProperties.class,EventInventoryCredentialProperties.class})
public class InventoryInitializationClientConfig {
    @Bean EventInventoryCredential eventInventoryCredential(EventInventoryCredentialProperties properties)throws java.io.IOException{return new EventInventoryCredential(properties);}
    @Bean @LoadBalanced RestClient.Builder initializationRestClientBuilder(EventPreparationProperties properties){
        var client=HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var factory=new JdkClientHttpRequestFactory(client);factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(factory);
    }
}
