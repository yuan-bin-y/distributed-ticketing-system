package com.byy.ticket.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;

/** 活动服务调用配置，服务名与 Nacos 注册名保持一致。 */
@Validated
@ConfigurationProperties(prefix = "ticket.clients.event")
public record EventClientProperties(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9-]*") String serviceId,
        Duration connectTimeout,
        Duration readTimeout
) {
    public EventClientProperties {
        serviceId = serviceId == null ? "ticket-event-service" : serviceId;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("服务调用超时必须大于零");
        }
    }
}
