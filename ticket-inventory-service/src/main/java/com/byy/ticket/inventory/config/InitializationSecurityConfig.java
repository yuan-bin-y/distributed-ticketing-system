package com.byy.ticket.inventory.config;
import com.byy.ticket.security.service.*;
import com.byy.ticket.common.result.Result;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
/** 初始化只接受Event，库存交易只接受Order；其余入口默认拒绝。 */
@Configuration
@EnableConfigurationProperties({EventInventoryCredentialProperties.class,OrderInventoryCredentialProperties.class})
public class InitializationSecurityConfig {
    @Bean EventInventoryCredential eventInventoryCredential(EventInventoryCredentialProperties properties)throws IOException{return new EventInventoryCredential(properties);}
    @Bean OrderInventoryCredential orderInventoryCredential(OrderInventoryCredentialProperties properties)throws IOException{return new OrderInventoryCredential(properties);}
    @Bean @Order(1)
    SecurityFilterChain initializationSecurity(HttpSecurity http,EventInventoryCredential credential,ObjectMapper json)throws Exception{
        common(http);
        return http.securityMatcher("/internal/stocks/initializations","/internal/stocks/initializations/**")
                .addFilterBefore(new OncePerRequestFilter(){
                    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
                        if(credential.matches(request.getHeader(EventInventoryCredential.HEADER)))SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated("ticket-event-service",null,List.of(new SimpleGrantedAuthority("ROLE_EVENT_SERVICE"))));
                        chain.doFilter(request,response);
                    }
                },BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth->auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST,"/internal/stocks/initializations").hasRole("EVENT_SERVICE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/stocks/initializations/{ticketTierId}").hasRole("EVENT_SERVICE")
                        .anyRequest().denyAll())
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->write(response,json,401))
                        .accessDeniedHandler((request,response,error)->write(response,json,403))).build();
    }
    @Bean @Order(2)
    SecurityFilterChain orderInventorySecurity(HttpSecurity http,OrderInventoryCredential credential,ObjectMapper json)throws Exception{
        common(http);
        return http.securityMatcher("/internal/**")
                .addFilterBefore(new ServiceCredentialFilter(OrderInventoryCredential.HEADER,credential::matches,"ticket-order-service","ORDER_SERVICE"),BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth->auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST,"/internal/stock-reservations","/internal/stock-reservations/{reservationId}/confirm","/internal/stock-reservations/{reservationId}/release").hasRole("ORDER_SERVICE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/stock-reservations/{reservationId}","/internal/stocks/{ticketTierId}").hasRole("ORDER_SERVICE")
                        .anyRequest().denyAll())
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->write(response,json,401))
                        .accessDeniedHandler((request,response,error)->write(response,json,403))).build();
    }
    /** 不公开库存写接口，也不允许未列出的内部方法。 */
    @Bean @Order(3) SecurityFilterChain inventoryFallback(HttpSecurity http,ObjectMapper json)throws Exception{
        common(http);return http.authorizeHttpRequests(auth->auth.anyRequest().denyAll())
                .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->write(response,json,401))
                        .accessDeniedHandler((request,response,error)->write(response,json,403))).build();
    }
    private void common(HttpSecurity http)throws Exception{http.csrf(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .requestCache(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS));}
    private void write(HttpServletResponse response,ObjectMapper json,int status)throws IOException{response.setStatus(status);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getWriter(),new Result<Void>(status==401?"UNAUTHORIZED":"FORBIDDEN","身份凭证缺失、无效或无权调用此接口",null,com.byy.ticket.common.trace.TraceIdContext.getOrCreate()));}
}
