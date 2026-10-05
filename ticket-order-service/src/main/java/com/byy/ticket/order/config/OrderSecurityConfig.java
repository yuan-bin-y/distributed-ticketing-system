package com.byy.ticket.order.config;

import com.byy.ticket.order.web.*;
import com.byy.ticket.security.service.PaymentOrderCredential;
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

/** 两条独立安全链：内部接口使用服务凭证；公共订单接口使用用户JWT。 */
@Configuration
public class OrderSecurityConfig {
    /** 优先匹配internal，仅Payment服务可POST通知，其余内部入口默认拒绝。 */
    @Bean
    @Order(1)
    public SecurityFilterChain internalSecurity(HttpSecurity http,PaymentOrderCredential credential,ObjectMapper json)throws Exception{
        common(http,json);
        return http.securityMatcher("/internal/**")
                .addFilterBefore(new PaymentServiceAuthenticationFilter(credential),BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth->auth.requestMatchers(HttpMethod.POST,"/internal/orders/payment-results").hasRole("PAYMENT_SERVICE")
                        .anyRequest().denyAll()).build();
    }
    /** JWT认证成功后自动建立Principal，已有OrderIdentityResolver继续读取用户ID。 */
    @Bean
    @Order(2)
    public SecurityFilterChain userSecurity(HttpSecurity http,JwtDecoder accessTokenDecoder,ObjectMapper json,OrderWorkflowProperties properties)throws Exception{
        common(http,json);
        if(properties.devIdentityEnabled())http.addFilterBefore(new DevUserAuthenticationFilter(),BearerTokenAuthenticationFilter.class);
        return http.authorizeHttpRequests(auth->auth.requestMatchers(HttpMethod.GET,"/api/orders/ping").permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.decoder(accessTokenDecoder))
                        .authenticationEntryPoint((request,response,exception)->write(response,json,401,"UNAUTHORIZED","请登录或更新登录凭证")))
                .build();
    }
    /** 无表单、Basic、默认logout或HTTP会话缓存，错误统一为Result。 */
    private void common(HttpSecurity http,ObjectMapper json)throws Exception{
        http.csrf(csrf->csrf.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable()).logout(logout->logout.disable())
                .requestCache(cache->cache.disable()).sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,exception)->write(response,json,401,"UNAUTHORIZED","请登录或提供服务凭证"))
                        .accessDeniedHandler((request,response,exception)->write(response,json,403,"UNAUTHORIZED","无权访问")));
    }
    /** Security过滤器异常不经过Controller异常处理器，因此在此输出统一JSON。 */
    private void write(HttpServletResponse response,ObjectMapper json,int status,String code,String message)throws java.io.IOException{
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getWriter(),new Result<Void>(code,message,null,com.byy.ticket.common.trace.TraceIdContext.getOrCreate()));
    }
}
