package com.byy.ticket.resilience;

import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.circuitbreaker.*;
import java.time.Duration;
import java.util.function.*;
import org.slf4j.LoggerFactory;

/** 复用保护机制；业务异常分类和拒绝后的处理由所属服务提供。 */
public final class HttpCallProtection {
    private final boolean enabled;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final Function<RuntimeException,RuntimeException> rejection;

    /** 每个客户端单例创建一次，每个调用方/下游独立统计，不共享跨进程额度。 */
    public HttpCallProtection(String name,HttpResilienceProperties.Policy policy,
            Predicate<Throwable> healthFailure,Function<RuntimeException,RuntimeException> rejection) {
        enabled=policy.enabled(); this.rejection=rejection;
        breaker=CircuitBreaker.of(name,CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(policy.window()).minimumNumberOfCalls(policy.minimumCalls())
                .failureRateThreshold(policy.failureThreshold()).waitDurationInOpenState(policy.openWait())
                .permittedNumberOfCallsInHalfOpenState(policy.halfOpenCalls())
                .maxWaitDurationInHalfOpenState(policy.halfOpenMaxWait())
                .ignoreException(error -> !healthFailure.test(error)).build());
        bulkhead=Bulkhead.of(name,BulkheadConfig.custom().maxConcurrentCalls(policy.concurrency())
                .maxWaitDuration(Duration.ZERO).build());
        breaker.getEventPublisher().onStateTransition(event -> LoggerFactory.getLogger(getClass())
                .info("HTTP调用保护 {} 状态变化: {}",name,event.getStateTransition()));
    }

    /** 先检查并发额度，再检查熔断；不自动重试，不生成备用成功结果。 */
    public <T> T execute(Supplier<T> call) {
        if(!enabled)return call.get();
        try { return Bulkhead.decorateSupplier(bulkhead,CircuitBreaker.decorateSupplier(breaker,call)).get(); }
        catch(CallNotPermittedException | BulkheadFullException denied) { throw rejection.apply(denied); }
    }
    /** 诊断与隔离验证使用，不暴露HTTP管理入口。 */
    public CircuitBreaker circuitBreaker() { return breaker; }
    public Bulkhead bulkhead() { return bulkhead; }
}
