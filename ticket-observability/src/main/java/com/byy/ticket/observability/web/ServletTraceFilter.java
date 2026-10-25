package com.byy.ticket.observability.web;

import com.byy.ticket.common.result.*;
import com.byy.ticket.common.trace.TraceIdContext;

import io.micrometer.tracing.Tracer;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.function.Predicate;

/** Servlet统一追踪边界；网关继续使用响应式上下文。业务服务显式选择认证存储异常。 */
public class ServletTraceFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final ObjectProvider<Tracer> tracers;
    private final Predicate<RuntimeException> sessionUnavailable;

    public ServletTraceFilter(
            ObjectMapper json,
            ObjectProvider<Tracer> tracers,
            Predicate<RuntimeException> sessionUnavailable) {
        this.json = json;
        this.tracers = tracers;
        this.sessionUnavailable = sessionUnavailable;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get(TraceIdContext.MDC_KEY);
        var tracer = tracers.getIfAvailable();
        var span = tracer == null ? null : tracer.currentSpan();
        String trace =
                TraceIdContext.setOrCreate(
                        span == null
                                ? request.getHeader(TraceIdContext.HTTP_HEADER)
                                : span.context().traceId());
        response.setHeader(TraceIdContext.HTTP_HEADER, trace);
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException failure) {
            if (!sessionUnavailable.test(failure) || response.isCommitted()) throw failure;
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(
                    response.getWriter(), Result.failure(ApiErrorCode.SERVICE_BUSY, "登录会话存储暂时不可用"));
        } finally {
            if (previous == null) TraceIdContext.clear();
            else MDC.put(TraceIdContext.MDC_KEY, previous);
        }
    }

    public static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (type.isInstance(cause)) return true;
        return false;
    }
}
