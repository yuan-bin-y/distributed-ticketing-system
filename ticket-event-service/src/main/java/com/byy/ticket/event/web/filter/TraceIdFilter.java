package com.byy.ticket.event.web.filter;

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

/** 请求结束后清理 MDC，避免线程复用时串号。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceIdContext.setOrCreate(request.getHeader(TraceIdContext.HTTP_HEADER));
        response.setHeader(TraceIdContext.HTTP_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceIdContext.clear();
        }
    }
}
