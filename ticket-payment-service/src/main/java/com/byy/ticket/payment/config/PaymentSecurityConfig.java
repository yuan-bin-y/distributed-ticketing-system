package com.byy.ticket.payment.config;

import com.byy.ticket.payment.web.*;
import com.byy.ticket.security.service.OrderPaymentCredential;
import com.byy.ticket.common.result.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/** 两条安全链分别识别Order服务与登录用户，内部凭证不能冒充用户付款。 */
@Configuration
public class PaymentSecurityConfig {
    /** 仅允许Order创建支付单、按订单查询及发起冲正，其他internal路径或方法拒绝。 */
    @Bean
    @Order(1)
    public SecurityFilterChain internalSecurity(HttpSecurity http,OrderPaymentCredential credential,ObjectMapper json)throws Exception{
        common(http,json);
        return http.securityMatcher("/internal/**")
                .addFilterBefore(new OrderServiceAuthenticationFilter(credential),BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth->auth
                        .requestMatchers(HttpMethod.POST,"/internal/payments","/internal/payment-reversals").hasRole("ORDER_SERVICE")
                        .requestMatchers(HttpMethod.GET,"/internal/payments/by-order/{orderNo}").hasRole("ORDER_SERVICE")
                        .anyRequest().denyAll()).build();
    }
    /** 查询及模拟支付必须有用户身份，模拟开关与支付单归属仍由原业务检查。 */
    @Bean
    @Order(2)
    public SecurityFilterChain userSecurity(HttpSecurity http,JwtDecoder accessTokenDecoder,ObjectMapper json,PaymentProperties properties)throws Exception{
        common(http,json);
        if(properties.devIdentityEnabled())http.addFilterBefore(new DevUserAuthenticationFilter(),BearerTokenAuthenticationFilter.class);
        return http.authorizeHttpRequests(auth->auth.requestMatchers(HttpMethod.GET,"/api/payments/ping").permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.decoder(accessTokenDecoder))
                        .authenticationEntryPoint((request,response,exception)->write(response,json,401,"UNAUTHORIZED","请登录或更新登录凭证"))).build();
    }
    /** 无HTTP会话、登录页面或默认退出端点；凭证由Auth统一管理。 */
    private void common(HttpSecurity http,ObjectMapper json)throws Exception{
        http.csrf(csrf->csrf.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable()).logout(logout->logout.disable())
                .requestCache(cache->cache.disable()).sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,exception)->write(response,json,401,"UNAUTHORIZED","请登录或提供服务凭证"))
                        .accessDeniedHandler((request,response,exception)->write(response,json,403,"UNAUTHORIZED","无权访问")));
    }
    /** 安全过滤器错误返回统一Result，不泄露凭证内容。 */
    private void write(HttpServletResponse response,ObjectMapper json,int status,String code,String message)throws java.io.IOException{
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(),new Result<Void>(code,message,null,com.byy.ticket.common.trace.TraceIdContext.getOrCreate()));
    }
}
