package com.byy.ticket.gateway.config;

import com.byy.ticket.gateway.web.GatewaySecurityResponses;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

/** 网关安全入口；用户接口使用Bearer JWT，网关不向公网转发internal接口。 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {
    /** 匿名仅开放注册、登录、刷新、启动探测和GET活动；其他请求必须认证。 */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder accessTokenDecoder,
                                                         GatewaySecurityResponses responses) {
        return http.csrf(csrf -> csrf.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(auth -> auth.pathMatchers("/internal/**").denyAll()
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST,"/api/auth/register","/api/auth/login","/api/auth/refresh").permitAll()
                        .pathMatchers(HttpMethod.GET,"/api/events/**","/api/auth/ping").permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((exchange, exception) -> responses.write(exchange,HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","请登录或更新登录凭证"))
                        .accessDeniedHandler((exchange, exception) -> responses.write(exchange,HttpStatus.FORBIDDEN,"UNAUTHORIZED","无权访问")))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtDecoder(accessTokenDecoder)
                        .jwtAuthenticationConverter(new org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter(
                                com.byy.ticket.security.jwt.UserRoleAuthorities.converter())))
                        .authenticationEntryPoint((exchange, exception) -> responses.write(exchange,HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","请登录或更新登录凭证")))
                .build();
    }
}
