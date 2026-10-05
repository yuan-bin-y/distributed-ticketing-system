package com.byy.ticket.order.web;

import com.byy.ticket.security.service.PaymentOrderCredential;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只安装在Order内部接口安全链；凭证只授予支付通知权限，不作为用户身份。 */
public final class PaymentServiceAuthenticationFilter extends OncePerRequestFilter {
    private final PaymentOrderCredential credential;
    /** 注入Payment/Order共享的独立开发服务凭证。 */
    public PaymentServiceAuthenticationFilter(PaymentOrderCredential credential){this.credential=credential;}
    /** 验证专用头后建立服务Principal；错误/缺失凭证交给Security拒绝。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(credential.matches(request.getHeader(PaymentOrderCredential.HEADER))){
            var auth=UsernamePasswordAuthenticationToken.authenticated("ticket-payment-service",null,
                    List.of(new SimpleGrantedAuthority("ROLE_PAYMENT_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request,response);
    }
}
