package com.byy.ticket.gateway.web;

import com.byy.ticket.common.trace.TraceIdContext;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** 在Security之前建立请求traceId，跨异步线程通过exchange传递，不写线程MDC。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayTraceFilter implements WebFilter {
    public static final String ATTRIBUTE = "ticket.gateway.traceId";
    /** 校验或生成编号，并写入请求/响应头供下游沿用。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String input = exchange.getRequest().getHeaders().getFirst(TraceIdContext.HTTP_HEADER);
        String trace = input != null && input.matches("[0-9a-fA-F]{32}") ? input.toLowerCase(Locale.ROOT)
                : UUID.randomUUID().toString().replace("-", "");
        exchange.getAttributes().put(ATTRIBUTE,trace);
        exchange.getResponse().getHeaders().set(TraceIdContext.HTTP_HEADER,trace);
        return chain.filter(exchange.mutate().request(exchange.getRequest().mutate()
                .headers(headers -> headers.set(TraceIdContext.HTTP_HEADER,trace)).build()).build());
    }
}
