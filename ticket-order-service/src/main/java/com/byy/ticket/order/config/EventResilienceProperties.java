package com.byy.ticket.order.config;

import java.time.Duration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 每个Order实例的Event调用保护参数；启动时校验，错误配置不能悄悄生效。 */
@Validated
@ConfigurationProperties(prefix = "ticket.clients.event.resilience")
public record EventResilienceProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") @Min(1) int slidingWindowSize,
        @DefaultValue("10") @Min(1) int minimumNumberOfCalls,
        @DefaultValue("50") @DecimalMin("1") @DecimalMax("100") float failureRateThreshold,
        @DefaultValue("10s") Duration openWait,
        @DefaultValue("3") @Min(1) int halfOpenCalls,
        @DefaultValue("10s") Duration halfOpenMaxWait,
        @DefaultValue("20") @Min(1) int maxConcurrentCalls) {

    /** 最少统计数不能超过窗口，熔断和试探等待期限必须为正。 */
    public EventResilienceProperties {
        if (minimumNumberOfCalls > slidingWindowSize || openWait == null || openWait.isNegative()
                || openWait.isZero() || halfOpenMaxWait == null || halfOpenMaxWait.isNegative()
                || halfOpenMaxWait.isZero()) {
            throw new IllegalArgumentException("Event熔断窗口或等待时间配置不合法");
        }
    }
}
