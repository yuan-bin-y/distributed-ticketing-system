package com.byy.ticket.order.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅显式开发模式、回环直连且无Authorization时接受开发用户头；默认不安装。 */
public final class DevUserAuthenticationFilter extends OncePerRequestFilter {
    /** 即使有开发用户头，携带错误Token也不能绕过JWT认证。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String address=request.getRemoteAddr(),user=request.getHeader("X-Dev-User-Id");
        if(request.getHeader("Authorization")==null&&("127.0.0.1".equals(address)||"::1".equals(address)||"0:0:0:0:0:0:0:1".equals(address))
                &&user!=null&&user.matches("[1-9][0-9]{0,18}")){
            try{
                if(Long.parseLong(user)>0)SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(user,null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            }catch(NumberFormatException ignored){}
        }
        chain.doFilter(request,response);
    }
}
