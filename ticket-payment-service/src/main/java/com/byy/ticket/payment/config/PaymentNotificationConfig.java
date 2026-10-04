package com.byy.ticket.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;

/** 支付服务按订单服务名发送通知，使用独立超时并开启后台调度。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentNotificationProperties.class)
public class PaymentNotificationConfig {
    /** 保留 Boot JSON 转换器；禁止重定向，写请求只由持久化任务重试。 */
    @Bean
    @LoadBalanced
    public RestClient.Builder orderNotificationRestClientBuilder(RestClientBuilderConfigurer configurer,
            PaymentNotificationProperties properties) {
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return configurer.configure(RestClient.builder()).requestFactory(factory);
    }
}
