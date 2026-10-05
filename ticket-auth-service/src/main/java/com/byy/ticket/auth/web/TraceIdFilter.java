package com.byy.ticket.auth.web;

import com.byy.ticket.common.trace.TraceIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.ObjectMapper;
import com.byy.ticket.common.result.ApiErrorCode;
import com.byy.ticket.common.result.Result;
import java.io.IOException;

/**
 * 为每个进入本服务的 HTTP 请求建立 traceId，并写入响应头和日志 MDC。
 * 跨服务 Client 会继续把相同编号发到下游，便于关联整条调用链。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    /** 注入 JSON 编码器，统一处理 Security 校验会话时发生的 Redis 存储异常。 */
    public TraceIdFilter(ObjectMapper json) { this.json = json; }
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
        } catch (DataAccessException exception) {
            // 会话存储不可用时拒绝认证，不能只凭 JWT 签名放行。
            if (response.isCommitted()) { throw exception; }
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(), Result.failure(ApiErrorCode.SERVICE_BUSY, "认证存储暂时不可用"));
        } finally {
            TraceIdContext.clear();
        }
    }
}
