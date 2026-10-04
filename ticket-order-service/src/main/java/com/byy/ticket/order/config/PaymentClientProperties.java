package com.byy.ticket.order.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

/**
 * 支付HTTP客户端配置；独立Builder避免与活动、库存的超时参数混淆。
 * @param serviceId 注册中心中的支付服务名称。
 * @param connectTimeout HTTP连接超时。
 * @param readTimeout HTTP响应读取超时。
 */
@Validated
@ConfigurationProperties(prefix = "ticket.clients.payment")
public record PaymentClientProperties(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9-]*") String serviceId,
        Duration connectTimeout, Duration readTimeout) {
    /** 未配置时使用支付服务名、2秒连接与5秒读取；超时必须大于零。 */
    public PaymentClientProperties {
        serviceId = serviceId == null ? "ticket-payment-service" : serviceId;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("支付服务调用超时必须大于零");
        }
    }
}
