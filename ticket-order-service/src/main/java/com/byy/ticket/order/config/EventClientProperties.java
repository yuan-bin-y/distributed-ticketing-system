package com.byy.ticket.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;

/**
 * 活动远程调用配置：从 ticket.clients.event 读取服务名和超时时间。
 * 服务名供负载均衡查找 Nacos 实例，不是固定主机地址。
 * 
 * @param serviceId Nacos 中的目标服务名，由负载均衡解析实例地址。
 * @param connectTimeout 建立 HTTP 连接的超时时间。
 * @param readTimeout 读取 HTTP 响应的超时时间。
 */
@Validated
@ConfigurationProperties(prefix = "ticket.clients.event")
public record EventClientProperties(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9-]*") String serviceId,
        Duration connectTimeout,
        Duration readTimeout
) {
    /**
     * 未配置时使用 ticket-event-service，连接超时 2 秒、读取超时 5 秒。
     * 检查超时必须大于零；服务名格式由 Bean Validation 校验。
     */
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
