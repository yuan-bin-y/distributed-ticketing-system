package com.byy.ticket.event.web.filter;

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
import com.byy.ticket.security.session.SessionStoreUnavailableException;
import com.byy.ticket.common.result.*;
import tools.jackson.databind.ObjectMapper;

/**
 * 为每个进入本服务的 HTTP 请求建立 traceId，并写入响应头和日志 MDC。
 * 跨服务 Client 会继续把相同编号发到下游，便于关联整条调用链。
 */
@Component
// Boot 的 HTTP Observation 先建立服务端 Span，本过滤器再读取真实 traceId。
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final ObjectProvider<Tracer> tracers;
    /** 注入JSON编码器处理Security层的会话存储故障。 */
    public TraceIdFilter(ObjectMapper json, ObjectProvider<Tracer> tracers) {
        this.json = json;
        this.tracers = tracers;
    }
    /**
     * 读取服务端 Span 的 Trace ID；无 Span 时回退到 X-Trace-Id，写入响应头和 MDC。
     * 执行后续过滤器和 Controller，完成后恢复外层 MDC，不破坏 Observation 的作用域。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get(TraceIdContext.MDC_KEY);
        Tracer tracer = tracers.getIfAvailable();
        var span = tracer == null ? null : tracer.currentSpan();
        // W3C traceparent 的上下文优先；X-Trace-Id 只是给用户查询日志的展示编号。
        String traceId = TraceIdContext.setOrCreate(span == null
                ? request.getHeader(TraceIdContext.HTTP_HEADER) : span.context().traceId());
        response.setHeader(TraceIdContext.HTTP_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException exception) {
            Throwable cause=exception;
            while(cause!=null && !(cause instanceof SessionStoreUnavailableException))cause=cause.getCause();
            if(cause==null || response.isCommitted())throw exception;
            response.setStatus(503);response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(),Result.failure(ApiErrorCode.SERVICE_BUSY,"登录会话存储暂时不可用"));
        } finally {
            // 恢复 Observation 管理的 MDC，避免覆盖外层 Span 的上下文。
            if (previous == null) TraceIdContext.clear();
            else MDC.put(TraceIdContext.MDC_KEY, previous);
        }
    }
}
