package com.byy.ticket.gateway.web;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Order只读请求的网络故障切换。初次仍由LoadBalancer选择，失败后避开本请求已失败的地址。 */
@Component
public class OrderReadRetryFilter implements GlobalFilter, Ordered {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OrderReadRetryFilter.class);
    private final ReactiveDiscoveryClient discovery;
    private final boolean enabled;
    private final int maxRetries;
    private final Duration attemptTimeout, totalTimeout;

    public OrderReadRetryFilter(ReactiveDiscoveryClient discovery,
            @Value("${ticket.order-read-retry.enabled:true}") boolean enabled,
            @Value("${ticket.order-read-retry.max-retries:2}") int maxRetries,
            @Value("${ticket.order-read-retry.attempt-timeout:1500ms}") Duration attemptTimeout,
            @Value("${ticket.order-read-retry.total-timeout:5s}") Duration totalTimeout) {
        if (maxRetries < 0 || maxRetries > 2 || attemptTimeout.isNegative() || attemptTimeout.isZero()
                || totalTimeout.isNegative() || totalTimeout.isZero()) {
            throw new IllegalArgumentException("只读重试最多两次，超时必须大于零");
        }
        this.discovery=discovery; this.enabled=enabled; this.maxRetries=maxRetries;
        this.attemptTimeout=attemptTimeout; this.totalTimeout=totalTimeout;
    }

    /** 在标准负载均衡之后、Netty真正发送HTTP之前执行；鉴权和入口限流不会重复运行。 */
    @Override public int getOrder() { return 10151; }

    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route=exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (!enabled || exchange.getRequest().getMethod()!=HttpMethod.GET || route==null
                || !"ticket-order-service".equals(route.getId())) { return chain.filter(exchange); }
        return attempt(exchange, chain, new HashSet<>(), 0)
                .timeout(totalTimeout)
                .onErrorMap(TimeoutException.class, error ->
                        new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"订单查询超过总时间预算",error));
    }

    /** 网络异常且响应未提交才重新发送；不重试业务HTTP状态、POST或已开始发送的响应。 */
    private Mono<Void> attempt(ServerWebExchange exchange, GatewayFilterChain chain,
                              Set<String> failed, int retries) {
        return Mono.defer(() -> {
            URI target=exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            return chain.filter(exchange).timeout(attemptTimeout).onErrorResume(error -> {
                if (exchange.getResponse().isCommitted() || !networkFailure(error)) { return Mono.error(error); }
                failed.add(address(target));
                if (retries>=maxRetries) { return unavailable(error); }
                // Nacos本地快照可能仍包含故障实例，必须按本次请求的失败地址集合排除。
                return discovery.getInstances("ticket-order-service")
                        .filter(instance -> !failed.contains(address(instance.getUri())))
                        .next()
                        .flatMap(instance -> {
                            log.info("order.read.retry from={} to={} attempt={}",address(target),address(instance.getUri()),retries+1);
                            ServerWebExchangeUtils.reset(exchange);
                            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
                                    replacement(target,instance));
                            return attempt(exchange,chain,failed,retries+1).thenReturn(Boolean.TRUE);
                        }).switchIfEmpty(unavailable(error)).then();
            });
        });
    }
    private static <T> Mono<T> unavailable(Throwable error) {
        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"订单服务暂时不可用",error));
    }
    private static String address(URI uri) { return uri.getHost()+":"+uri.getPort(); }
    private static URI replacement(URI original, ServiceInstance instance) {
        // 保留原始转义后的路径和查询串，避免重编码订单请求参数。
        return URI.create(instance.getUri().toString()+original.getRawPath()
                +(original.getRawQuery()==null?"":"?"+original.getRawQuery()));
    }
    private static boolean networkFailure(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof IOException || cause instanceof TimeoutException
                    || cause instanceof org.springframework.cloud.gateway.support.TimeoutException) { return true; }
        }
        return false;
    }
}
