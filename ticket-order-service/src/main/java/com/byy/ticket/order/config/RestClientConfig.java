package com.byy.ticket.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 为活动、库存两个远程服务分别创建 RestClient.Builder，配置连接和读取超时。
 * LoadBalanced 标记启用服务名解析；Qualifier 区分两个 Builder，避免注入混淆。
 */
@Configuration
@EnableConfigurationProperties({EventClientProperties.class, InventoryClientProperties.class})
public class RestClientConfig {
    /**
     * 创建专门调用活动服务的 Builder，使用活动客户端的超时配置并启用负载均衡。
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder eventRestClientBuilder(
            RestClientBuilderConfigurer configurer, EventClientProperties properties) {
        return createBuilder(configurer, properties.connectTimeout(), properties.readTimeout());
    }

    /**
     * 创建专门调用库存服务的 Builder，使用库存客户端的超时配置并启用负载均衡。
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder inventoryRestClientBuilder(
            RestClientBuilderConfigurer configurer, InventoryClientProperties properties) {
        return createBuilder(configurer, properties.connectTimeout(), properties.readTimeout());
    }

    /**
     * 配置 JDK HTTP 客户端的连接超时和读取超时，禁止自动跟随重定向。
     * 使用 Boot 的 configurer 保留 JSON 转换器等配置，再包装请求工厂以识别正文读取超时。
     */
    private RestClient.Builder createBuilder(RestClientBuilderConfigurer configurer,
                                            Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        // 保留 Boot 配置的 JSON 转换器，再指定连接及读取超时。
        return configurer.configure(RestClient.builder())
                .requestFactory(new ReadTimeoutRequestFactory(factory, readTimeout));
    }
}
