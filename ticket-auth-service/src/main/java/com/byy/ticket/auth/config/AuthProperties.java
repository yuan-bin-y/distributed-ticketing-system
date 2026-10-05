package com.byy.ticket.auth.config;

import java.time.Duration;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT 声明、有效期、持久化 RSA 密钥路径和 Redis 键前缀。 */
@ConfigurationProperties("ticket.auth")
public record AuthProperties(String issuer, String audience, Duration accessTtl, Duration refreshTtl,
                             Path privateKeyPath, Path publicKeyPath, String redisPrefix) {
    /** 启动时拒绝缺失或不合理的认证配置。 */
    public AuthProperties {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || redisPrefix == null || redisPrefix.isBlank() || privateKeyPath == null || publicKeyPath == null
                || accessTtl == null || accessTtl.toSeconds() < 1 || refreshTtl == null
                || refreshTtl.compareTo(accessTtl) <= 0) {
            throw new IllegalArgumentException("认证配置不完整，refresh-ttl 必须大于 access-ttl");
        }
    }
}
