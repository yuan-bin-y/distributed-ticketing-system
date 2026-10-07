package com.byy.ticket.inventory.web.filter;

import com.byy.ticket.common.trace.TraceIdContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.MDC;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * 为每个进入本服务的 HTTP 请求建立 traceId，并写入响应头和日志 MDC。
 * 跨服务 Client 会继续把相同编号发到下游，便于关联整条调用链。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdFilter extends OncePerRequestFilter {
    private final ObjectProvider<Tracer> tracers;
    public TraceIdFilter(ObjectProvider<Tracer> tracers) { this.tracers = tracers; }
    /**
     * 读取请求中的 X-Trace-Id，校验或生成编号，再写入响应头。
     * 执行后续过滤器和 Controller；无论成功或异常，都在 finally 清理当前线程 MDC。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get(TraceIdContext.MDC_KEY);
        Tracer tracer = tracers.getIfAvailable();
        var span = tracer == null ? null : tracer.currentSpan();
        String traceId = TraceIdContext.setOrCreate(span == null
                ? request.getHeader(TraceIdContext.HTTP_HEADER) : span.context().traceId());
        response.setHeader(TraceIdContext.HTTP_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) TraceIdContext.clear();
            else MDC.put(TraceIdContext.MDC_KEY, previous);
        }
    }
}
