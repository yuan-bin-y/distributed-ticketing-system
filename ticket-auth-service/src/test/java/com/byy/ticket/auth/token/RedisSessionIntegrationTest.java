package com.byy.ticket.auth.token;

import static org.junit.jupiter.api.Assertions.*;

import com.byy.ticket.auth.config.AuthProperties;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

@EnabledIfSystemProperty(named = "ticket.test.redis", matches = "true")
class RedisSessionIntegrationTest {
    static LettuceConnectionFactory connection;
    RedisSessionService sessions;
    final String user = UUID.randomUUID().toString();

    @BeforeAll
    static void connect() {
        connection =
                new LettuceConnectionFactory(
                        "127.0.0.1", Integer.getInteger("ticket.test.redis.port", 16379));
        connection.afterPropertiesSet();
        connection.start();
    }

    @AfterAll
    static void close() {
        connection.destroy();
    }

    @BeforeEach
    void setup() {
        sessions =
                new RedisSessionService(
                        new StringRedisTemplate(connection),
                        new AuthProperties(
                                "test",
                                "test",
                                Duration.ofMinutes(1),
                                Duration.ofHours(1),
                                Path.of("unused"),
                                Path.of("unused"),
                                "test:session:"));
    }

    @Test
    void concurrentRefreshHasExactlyOneWinner() throws Exception {
        sessions.create(user, "device", "original", Duration.ofMinutes(1));
        var workers = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var tasks = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < 16; i++)
                tasks.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    return sessions.rotate(
                                            user,
                                            "device",
                                            "original",
                                            UUID.randomUUID().toString(),
                                            Duration.ofMinutes(1));
                                }));
            start.countDown();
            int winners = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        } finally {
            workers.shutdownNow();
            sessions.revoke(user, "device");
        }
    }

    @Test
    void logoutCannotBeUndoneByRefreshAndDoesNotRevokeOtherDevice() {
        sessions.create(user, "a", "jti", Duration.ofMinutes(1));
        sessions.create(user, "b", "jti", Duration.ofMinutes(1));
        try {
            sessions.revoke(user, "a");
            assertFalse(sessions.active(user, "a"));
            assertFalse(sessions.rotate(user, "a", "jti", "new", Duration.ofMinutes(1)));
            assertTrue(sessions.active(user, "b"));
            assertFalse(sessions.rotate("another", "b", "jti", "new", Duration.ofMinutes(1)));
        } finally {
            sessions.revoke(user, "b");
        }
    }

    @Test
    void expiredSessionCannotRefresh() throws Exception {
        sessions.create(user, "short", "jti", Duration.ofMillis(50));
        Thread.sleep(100);
        assertFalse(sessions.active(user, "short"));
        assertFalse(sessions.rotate(user, "short", "jti", "new", Duration.ofMinutes(1)));
    }
}
