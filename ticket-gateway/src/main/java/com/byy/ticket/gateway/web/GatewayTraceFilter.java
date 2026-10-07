package com.byy.ticket.gateway.web;

import com.byy.ticket.common.trace.TraceIdContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
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
    /** 优先使用 HTTP Observation 的 Trace ID，写入响应与兼容请求头供日志关联。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 订阅时从 Reactor Context 读取服务端 Observation，跨线程不依赖手工 MDC。
        return Mono.deferContextual(context -> {
            Observation observation = context.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            TracingContext tracing = observation == null ? null : observation.getContext().get(TracingContext.class);
            String input = exchange.getRequest().getHeaders().getFirst(TraceIdContext.HTTP_HEADER);
            String trace = input != null && input.matches("[0-9a-fA-F]{32}") ? input.toLowerCase(Locale.ROOT)
                    : UUID.randomUUID().toString().replace("-", "");
            if (tracing != null && tracing.getSpan() != null) trace = tracing.getSpan().context().traceId();
            final String responseTrace = trace;
            exchange.getAttributes().put(ATTRIBUTE, trace);
            exchange.getResponse().getHeaders().set(TraceIdContext.HTTP_HEADER, trace);
            return chain.filter(exchange.mutate().request(exchange.getRequest().mutate()
                    .headers(headers -> headers.set(TraceIdContext.HTTP_HEADER, responseTrace)).build()).build());
        });
    }
}
