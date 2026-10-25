package com.byy.ticket.gateway.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@EnabledIfSystemProperty(named = "ticket.test.redis", matches = "true")
class AuthRateLimitIntegrationTest {
    @Test
    void loginRegisterRefreshShareIpBudgetWhileForgedHeadersCannotResetIt() {
        var connection =
                new LettuceConnectionFactory(
                        "127.0.0.1", Integer.getInteger("ticket.test.redis.port", 16379));
        connection.afterPropertiesSet();
        connection.start();
        try {
            var filter =
                    new GatewayRateLimitFilter(
                            new ReactiveStringRedisTemplate(connection),
                            new GatewaySecurityResponses(new ObjectMapper()),
                            true,
                            "test:{limit}:" + UUID.randomUUID() + ":",
                            100,
                            100,
                            100,
                            100,
                            100,
                            100,
                            100,
                            100,
                            new ClientIpResolver(""),
                            10,
                            10,
                            1,
                            2);
            var forwarded = new AtomicInteger();
            for (String path : new String[] {"login", "register", "refresh"}) {
                var exchange =
                        MockServerWebExchange.from(
                                MockServerHttpRequest.post("/api/auth/" + path)
                                        .remoteAddress(new InetSocketAddress("127.0.0.1", 1234))
                                        .header("X-Forwarded-For", UUID.randomUUID().toString())
                                        .build());
                filter.filter(
                                exchange,
                                ignored -> {
                                    forwarded.incrementAndGet();
                                    return Mono.empty();
                                })
                        .block();
                if (path.equals("refresh"))
                    assertEquals(
                            HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
            }
            assertEquals(2, forwarded.get());
        } finally {
            connection.destroy();
        }
    }
}
