package com.byy.ticket.payment.web;

import com.byy.ticket.payment.config.PaymentProperties;
import com.byy.ticket.payment.exception.PaymentIdentityException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** 公共支付入口的身份解析；Auth 未接入时只有显式开发配置才接受开发身份头。 */
@Component
public class PaymentIdentityResolver {
    private final PaymentProperties properties;

    /** 注入开发身份开关，默认关闭。 */
    public PaymentIdentityResolver(PaymentProperties properties) { this.properties = properties; }

    /** 优先读取服务端 Principal；不信任请求正文 userId 或普通 X-User-Id。 */
    public Long requireUser(HttpServletRequest request) {
        String value = request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName();
        if (value == null && properties.devIdentityEnabled()) { value = request.getHeader("X-Dev-User-Id"); }
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) { throw new PaymentIdentityException(); }
        try { return Long.valueOf(value); }
        catch (NumberFormatException exception) { throw new PaymentIdentityException(); }
    }
}
