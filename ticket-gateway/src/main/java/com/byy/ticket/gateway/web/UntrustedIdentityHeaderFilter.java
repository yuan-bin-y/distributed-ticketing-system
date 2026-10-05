package com.byy.ticket.gateway.web;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** 用户只能通过Token提供身份；不向下游转发客户端伪造的开发用户/普通用户头。 */
@Component
@Order(-90)
public class UntrustedIdentityHeaderFilter implements WebFilter {
    /** 保留Authorization供业务服务后续独立验签；不把网关Principal转换成可伪造用户头。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove("X-Dev-User-Id"); headers.remove("X-User-Id");
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}
