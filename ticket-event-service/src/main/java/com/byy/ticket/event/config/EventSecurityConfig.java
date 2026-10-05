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
/** 网关和Event各自验证管理员Token；直接访问Event也不能绕过权限。 */
@Configuration
public class EventSecurityConfig {
    @Bean SecurityFilterChain eventSecurity(HttpSecurity http,JwtDecoder decoder,ObjectMapper json)throws Exception{
        return http.csrf(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
                .requestCache(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth.requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/events/**","/internal/ticket-tiers/{ticketTierId}/purchase-rule").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->write(response,json,401))
                        .accessDeniedHandler((request,response,error)->write(response,json,403)))
                .oauth2ResourceServer(oauth->oauth.jwt(jwt->jwt.decoder(decoder).jwtAuthenticationConverter(UserRoleAuthorities.converter()))
                        .authenticationEntryPoint((request,response,error)->write(response,json,401))).build();
    }
    private void write(HttpServletResponse response,ObjectMapper json,int status)throws java.io.IOException{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getWriter(),new Result<Void>("UNAUTHORIZED","需要管理员身份或有效登录凭证",null,com.byy.ticket.common.trace.TraceIdContext.getOrCreate()));}
}
