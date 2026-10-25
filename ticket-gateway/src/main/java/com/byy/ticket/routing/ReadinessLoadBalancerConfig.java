package com.byy.ticket.routing;

import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/** 位于网关组件扫描之外，只在每个LoadBalancer子容器内创建。 */
@Configuration(proxyBeanMethods = false)
public class ReadinessLoadBalancerConfig {
    @Bean
    ServiceInstanceListSupplier readyInstances(ConfigurableApplicationContext context) {
        var discovery = ServiceInstanceListSupplier.builder().withDiscoveryClient().build(context);
        return new ReadinessInstanceSupplier(
                discovery, WebClient.builder().build(), Duration.ofSeconds(3));
    }
}
