package com.byy.ticket.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;

/** 创建具备服务发现和负载均衡能力的 HTTP 客户端。 */
@Configuration
@EnableConfigurationProperties(EventClientProperties.class)
public class RestClientConfig {
    @Bean
    @LoadBalanced
    public RestClient.Builder eventRestClientBuilder(
            RestClientBuilderConfigurer configurer, EventClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        // 保留 Boot 配置的 JSON 转换器，再指定连接及读取超时。
        return configurer.configure(RestClient.builder()).requestFactory(factory);
    }
}
