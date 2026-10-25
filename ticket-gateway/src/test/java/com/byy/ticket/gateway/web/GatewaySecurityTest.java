package com.byy.ticket.gateway.web;

import com.byy.ticket.gateway.config.GatewaySecurityConfig;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

class GatewaySecurityTest {
    AnnotationConfigApplicationContext context;
    WebTestClient http;
    @Configuration @EnableWebFlux @Import({GatewaySecurityConfig.class, GatewaySecurityResponses.class})
    static class Config {
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean ReactiveJwtDecoder accessTokenDecoder() {
            ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
            when(decoder.decode("user")).thenReturn(Mono.just(Jwt.withTokenValue("user").header("alg","RS256").subject("1").claim("role","USER").build()));
            return decoder;
        }
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }
    @RestController static class Endpoints {
        @GetMapping({"/api/events", "/api/orders", "/internal/test", "/api/admin/test"}) String get() { return "ok"; }
        @PostMapping("/api/auth/login") String login() { return "ok"; }
    }
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        http = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { context.close(); }
    @Test void openPathsAndAuthenticatedOrderHaveDifferentBoundaries() {
        http.get().uri("/api/events").exchange().expectStatus().isOk();
        http.post().uri("/api/auth/login").exchange().expectStatus().isOk();
        http.get().uri("/api/orders").exchange().expectStatus().isUnauthorized();
        http.get().uri("/api/orders").headers(h -> h.setBearerAuth("user")).exchange().expectStatus().isOk();
    }
    @Test void userCannotReachAdminOrInternalPaths() {
        http.get().uri("/api/admin/test").headers(h -> h.setBearerAuth("user")).exchange().expectStatus().isForbidden();
        http.get().uri("/internal/test").headers(h -> h.setBearerAuth("user")).exchange().expectStatus().isForbidden();
    }
}
