package com.byy.ticket.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** 通知任务与订单 HTTP 客户端配置；限制批次并确保租约覆盖正常网络等待。 */
@ConfigurationProperties("ticket.payment.notification")
public record PaymentNotificationProperties(Boolean enabled, String serviceId, Duration connectTimeout,
        Duration readTimeout, Duration leaseDuration, Integer batchSize, Duration retryBase, Duration retryMax) {
    /** 应用缺省值并在启动时拒绝不合法配置，避免零间隔重试和过短租约。 */
    public PaymentNotificationProperties {
        enabled = enabled == null ? true : enabled;
        serviceId = serviceId == null ? "ticket-order-service" : serviceId;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
        batchSize = batchSize == null ? 20 : batchSize;
        retryBase = retryBase == null ? Duration.ofSeconds(5) : retryBase;
        retryMax = retryMax == null ? Duration.ofMinutes(5) : retryMax;
        if (!serviceId.matches("[a-zA-Z0-9][a-zA-Z0-9-]*") || batchSize < 1 || batchSize > 200
                || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout.isNegative() || readTimeout.isZero()
                || retryBase.toMillis() < 1 || retryMax.compareTo(retryBase) < 0
                || leaseDuration.compareTo(connectTimeout.plus(readTimeout).plusSeconds(5)) < 0) {
            throw new IllegalArgumentException("支付通知配置不合法，租约必须覆盖客户端超时并留出余量");
        }
    }
}
