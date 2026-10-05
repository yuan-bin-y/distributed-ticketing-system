package com.byy.ticket.payment.web.filter;

import com.byy.ticket.common.trace.TraceIdContext;
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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    /** 注入JSON编码器处理Security层的会话存储故障。 */
    public TraceIdFilter(ObjectMapper json) { this.json=json; }
    /**
     * 读取请求中的 X-Trace-Id，校验或生成编号，再写入响应头。
     * 执行后续过滤器和 Controller；无论成功或异常，都在 finally 清理当前线程 MDC。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceIdContext.setOrCreate(request.getHeader(TraceIdContext.HTTP_HEADER));
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
            TraceIdContext.clear();
        }
    }
}
