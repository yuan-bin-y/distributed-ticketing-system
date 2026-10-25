package com.byy.ticket.payment.security;

import com.byy.ticket.payment.config.PaymentSecurityConfig;
import com.byy.ticket.payment.config.PaymentProperties;
import com.byy.ticket.security.service.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用实际安全链；仅替换验签器，不依赖启动全部业务和数据库。 */
class SecurityBoundaryTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc http;
    @Configuration @EnableWebSecurity @EnableWebMvc @Import(PaymentSecurityConfig.class)
    static class Config {
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean JwtDecoder accessTokenDecoder() {
            JwtDecoder decoder = mock(JwtDecoder.class);
            when(decoder.decode("user")).thenReturn(token("USER"));
            when(decoder.decode("admin")).thenReturn(token("ADMIN"));
            when(decoder.decode("invalid")).thenThrow(new BadJwtException("test invalid signature"));
            return decoder;
        }
        @Bean PaymentProperties properties() { return new PaymentProperties(false,false); }
        @Bean OrderPaymentCredential credential() throws Exception { return new OrderPaymentCredential(new OrderPaymentCredentialProperties("a".repeat(64), null)); }
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }
    static Jwt token(String role) { return Jwt.withTokenValue("test").header("alg","RS256")
            .subject("1").claim("role",role).build(); }
    @RestController static class Endpoints {
        @GetMapping({"/api/payments/ping","/api/payments/mine","/api/orders/admin/reviews"}) String get() { return "ok"; }
        @PostMapping("/api/auth/logout") String logout() { return "ok"; }
        @PostMapping("/internal/payments") String internal() { return "ok"; }
    }
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh();
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { context.close(); }
    @Test void anonymousAndInvalidTokenAreDeniedWhilePingIsPublic() throws Exception {
        http.perform(get("/api/payments/ping")).andExpect(status().isOk());
        http.perform(get("/api/payments/mine")).andExpect(status().isUnauthorized());
        http.perform(get("/api/payments/mine").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        http.perform(get("/api/payments/mine").header("Authorization","Bearer user")).andExpect(status().isOk());
    }
    
    @Test void userTokenCannotReplaceInternalCredential() throws Exception {
        String path = "/internal/payments";
        http.perform(post(path).header("Authorization","Bearer admin")).andExpect(status().is4xxClientError());
        http.perform(post(path).header("X-Order-Payment-Credential","b".repeat(64))).andExpect(status().is4xxClientError());
        http.perform(post(path).header("X-Order-Payment-Credential","a".repeat(64))).andExpect(status().isOk());
    }
}

