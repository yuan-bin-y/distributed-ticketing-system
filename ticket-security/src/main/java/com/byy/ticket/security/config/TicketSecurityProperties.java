package com.byy.ticket.security.config;

import java.time.Duration;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 使用与Auth相同的issuer、audience及Redis前缀；网关不持有签发私钥。 */
@ConfigurationProperties("ticket.security")
public record TicketSecurityProperties(String issuer, String audience, URI jwkSetUri,
                                       String redisPrefix, Duration jwkTimeout) {
    /** 启动时拒绝缺失配置和无效公钥地址。 */
    public TicketSecurityProperties {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || redisPrefix == null || redisPrefix.isBlank() || jwkTimeout == null
                || jwkTimeout.toMillis() < 1 || jwkTimeout.toMillis() > Integer.MAX_VALUE
                || jwkSetUri == null || jwkSetUri.getHost() == null
                || !("http".equals(jwkSetUri.getScheme()) || "https".equals(jwkSetUri.getScheme()))) {
            throw new IllegalArgumentException("认证校验配置不完整或无效");
        }
    }
}
