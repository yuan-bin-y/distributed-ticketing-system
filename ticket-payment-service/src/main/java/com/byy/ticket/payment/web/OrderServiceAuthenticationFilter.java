package com.byy.ticket.payment.web;

import com.byy.ticket.security.service.OrderPaymentCredential;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只安装在Payment内部接口安全链；凭证只授予支付创建、查询及冲正权限，不作为用户身份。 */
public final class OrderServiceAuthenticationFilter extends OncePerRequestFilter {
    private final OrderPaymentCredential credential;
    /** 注入Order/Payment共享的独立开发服务凭证。 */
    public OrderServiceAuthenticationFilter(OrderPaymentCredential credential){this.credential=credential;}
    /** 验证专用头后建立服务Principal；错误/缺失凭证交给Security拒绝。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(credential.matches(request.getHeader(OrderPaymentCredential.HEADER))){
            var auth=UsernamePasswordAuthenticationToken.authenticated("ticket-order-service",null,
                    List.of(new SimpleGrantedAuthority("ROLE_ORDER_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request,response);
    }
}
