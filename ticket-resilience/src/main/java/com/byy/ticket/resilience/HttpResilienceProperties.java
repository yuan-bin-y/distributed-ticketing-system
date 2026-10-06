package com.byy.ticket.resilience;

import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 各调用服务按下游配置；只包含技术参数，不包含业务实体。 */
@Validated
@ConfigurationProperties("ticket.http-resilience")
public record HttpResilienceProperties(@DefaultValue @Valid Policy inventory,
        @DefaultValue @Valid Policy payment, @DefaultValue @Valid Policy order) {
    /** 默认策略供非Spring验证构造使用；Spring启动时通过绑定和校验读取配置。 */
    public static Policy defaults() { return new Policy(true,20,10,50,Duration.ofSeconds(10),3,Duration.ofSeconds(10),20); }

    public record Policy(@DefaultValue("true") boolean enabled,
            @DefaultValue("20") @Min(1) int window,
            @DefaultValue("10") @Min(1) int minimumCalls,
            @DefaultValue("50") @DecimalMin("1") @DecimalMax("100") float failureThreshold,
            @DefaultValue("10s") Duration openWait,
            @DefaultValue("3") @Min(1) int halfOpenCalls,
            @DefaultValue("10s") Duration halfOpenMaxWait,
            @DefaultValue("20") @Min(1) int concurrency) {
        /** 同时校验直接Java构造和Spring绑定，防止不合法的策略运行。 */
        public Policy {
            if(window<1 || minimumCalls<1 || minimumCalls>window || !Float.isFinite(failureThreshold)
                    || failureThreshold<1 || failureThreshold>100 || halfOpenCalls<1 || concurrency<1
                    || openWait==null || openWait.isZero() || openWait.isNegative()
                    || halfOpenMaxWait==null || halfOpenMaxWait.isZero() || halfOpenMaxWait.isNegative())
                throw new IllegalArgumentException("HTTP调用保护配置不合法");
        }
    }
}
