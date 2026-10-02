package com.byy.ticket.order.web.filter;

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

/** 为订单请求建立 traceId，远程调用继续传递该标识。 */
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
