package com.byy.ticket.event.config;
import com.byy.ticket.security.jwt.UserRoleAuthorities;
import com.byy.ticket.common.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;
import com.byy.ticket.security.service.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
/** 网关和Event各自验证管理员Token；直接访问Event也不能绕过权限。 */
@Configuration
@EnableConfigurationProperties(OrderEventCredentialProperties.class)
public class EventSecurityConfig {
    /** 独立管理端口开放只读健康和指标；业务端口不公开Prometheus。 */
    @org.springframework.context.annotation.Bean
    @org.springframework.core.annotation.Order(0)
    public org.springframework.security.web.SecurityFilterChain managementSecurity(
            org.springframework.security.config.annotation.web.builders.HttpSecurity http,
            @org.springframework.beans.factory.annotation.Value("${management.server.port:-1}") int port) throws Exception {
        return http.securityMatcher(request -> port>0 && request.getLocalPort()==port)
                .csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
                .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.GET,
                        "/actuator/health","/actuator/prometheus").permitAll().anyRequest().denyAll()).build();
    }
    @Bean OrderEventCredential orderEventCredential(OrderEventCredentialProperties properties)throws java.io.IOException{return new OrderEventCredential(properties);}
    /** 内部购票规则只接受Order服务身份；用户和管理员JWT不能替代服务凭证。 */
    @Bean @Order(1) SecurityFilterChain internalEventSecurity(HttpSecurity http,OrderEventCredential credential,ObjectMapper json)throws Exception{
        return http.securityMatcher("/internal/**").csrf(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .requestCache(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ServiceCredentialFilter(OrderEventCredential.HEADER,credential::matches,"ticket-order-service","ORDER_SERVICE"),BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth->auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/ticket-tiers/{ticketTierId}/purchase-rule").hasRole("ORDER_SERVICE").anyRequest().denyAll())
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->write(response,json,401))
                        .accessDeniedHandler((request,response,error)->write(response,json,403))).build();
    }
    @Bean @Order(2) SecurityFilterChain eventSecurity(HttpSecurity http,JwtDecoder decoder,ObjectMapper json)throws Exception{
        return http.csrf(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .requestCache(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth.requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/events/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->write(response,json,401))
                        .accessDeniedHandler((request,response,error)->write(response,json,403)))
                .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.decoder(decoder).jwtAuthenticationConverter(UserRoleAuthorities.converter()))
                        .authenticationEntryPoint((request,response,error)->write(response,json,401))).build();
    }
    private void write(HttpServletResponse response,ObjectMapper json,int status)throws java.io.IOException{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getWriter(),new Result<Void>(status==401?"UNAUTHORIZED":"FORBIDDEN","身份凭证缺失、无效或无权调用此接口",null,com.byy.ticket.common.trace.TraceIdContext.getOrCreate()));}
}
