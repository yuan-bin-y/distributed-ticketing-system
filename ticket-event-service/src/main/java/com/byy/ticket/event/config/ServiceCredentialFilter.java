package com.byy.ticket.event.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅安装在内部接口安全链中；凭证匹配后建立本次请求的服务身份。 */
public final class ServiceCredentialFilter extends OncePerRequestFilter {
    private final String header;
    private final Predicate<String> matches;
    private final String principal;
    private final String role;

    /** 接收端配置指定请求头、校验器及服务权限，不能由请求自行指定角色。 */
    public ServiceCredentialFilter(String header, Predicate<String> matches, String principal, String role) {
        this.header=header; this.matches=matches; this.principal=principal; this.role=role;
    }

    /** 凭证错误或缺失时保持匿名，由安全链返回401；业务Controller不会执行。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain)throws IOException,ServletException {
        if(matches.test(request.getHeader(header))) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    principal,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
        }
        chain.doFilter(request,response);
    }
}
