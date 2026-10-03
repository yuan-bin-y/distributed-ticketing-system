package com.byy.ticket.order.web;

import com.byy.ticket.order.config.OrderWorkflowProperties;
import com.byy.ticket.order.exception.OrderIdentityException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** 身份接入点：优先使用服务端 Principal；Auth 未接入时只有显式开发配置才接受开发身份头。 */
@Component
public class OrderIdentityResolver {
    private final OrderWorkflowProperties properties;

    /** 注入开发身份开关。 */
    public OrderIdentityResolver(OrderWorkflowProperties properties) { this.properties = properties; }

    /** 解析正整数用户 ID；不读取请求正文 userId，也不信任普通 X-User-Id。 */
    public Long requireUser(HttpServletRequest request) {
        String value = request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName();
        if (value == null && properties.devIdentityEnabled()) { value = request.getHeader("X-Dev-User-Id"); }
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) { throw new OrderIdentityException(); }
        try { return Long.valueOf(value); }
        catch (NumberFormatException exception) { throw new OrderIdentityException(); }
    }
}
