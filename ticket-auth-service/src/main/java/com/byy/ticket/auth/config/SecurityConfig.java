package com.byy.ticket.auth.config;

import com.byy.ticket.common.result.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/** 无 Cookie 登录态；开放注册、登录、刷新，退出必须携带有效 Access Token。 */
@Configuration
public class SecurityConfig {
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
    /** BCrypt 保存摘要，不把原始密码落库。 */
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    /** Security 过滤器异常也返回统一 Result，不返回默认登录页面。 */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        var entryPoint = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, exception) -> {
            response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
            json.writeValue(response.getWriter(), Result.failure(ApiErrorCode.UNAUTHORIZED, "请登录或更新登录凭证"));
        };
        http.csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/ping", "/.well-known/jwks.json").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                    json.writeValue(response.getWriter(), Result.failure(ApiErrorCode.UNAUTHORIZED, "无权访问"));
                }))
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint));
        return http.build();
    }
}
