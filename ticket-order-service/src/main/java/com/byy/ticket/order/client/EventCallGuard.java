package com.byy.ticket.order.client;

import com.byy.ticket.order.client.dto.TicketPurchaseRuleResponse;
import com.byy.ticket.order.client.exception.EventServiceCallException;
import com.byy.ticket.order.client.exception.EventServiceCallException.Reason;
import com.byy.ticket.order.config.EventResilienceProperties;
import com.byy.ticket.resilience.HttpCallProtection;
import com.byy.ticket.resilience.HttpResilienceProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Event保留原业务入口和配置，底层保护机制复用ticket-resilience。 */
@Component
@EnableConfigurationProperties(EventResilienceProperties.class)
public class EventCallGuard {
    private final EventClient client;
    private final HttpCallProtection protection;

    /** 单例保护器共享本实例内的统计；参数沿用已验证的Event配置。 */
    public EventCallGuard(EventClient client,EventResilienceProperties properties) {
        this.client=client;
        var policy=new HttpResilienceProperties.Policy(properties.enabled(),properties.slidingWindowSize(),
                properties.minimumNumberOfCalls(),properties.failureRateThreshold(),properties.openWait(),
                properties.halfOpenCalls(),properties.halfOpenMaxWait(),properties.maxConcurrentCalls());
        protection=new HttpCallProtection("order-event",policy,
                error -> error instanceof EventServiceCallException remote && remote.isCircuitBreakerFailure(),
                denied -> new EventServiceCallException(Reason.UNAVAILABLE,
                        denied instanceof BulkheadFullException ? "活动查询繁忙，请稍后重试" : "活动服务正在恢复，请稍后重试",denied));
    }

    /** 校验参数后申请并发额度、检查熔断状态；HTTP仍由EventClient执行。 */
    public TicketPurchaseRuleResponse getPurchaseRule(Long ticketTierId) {
        if(ticketTierId==null||ticketTierId<1)throw new IllegalArgumentException("票档ID必须大于零");
        return protection.execute(() -> client.getPurchaseRule(ticketTierId));
    }

    /** 本地诊断与验证使用，不暴露HTTP管理入口。 */
    public CircuitBreaker circuitBreaker() { return protection.circuitBreaker(); }
    public Bulkhead bulkhead() { return protection.bulkhead(); }
}
