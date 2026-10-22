package com.byy.ticket.payment.mq;

import com.byy.ticket.payment.model.OutboxEvent;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 有限的自动发布预算；停止自动尝试不改变付款事实，也不删除消息。 */
@Component
public class OutboxRetryPolicy {
    private final int maxAttempts;
    private final Duration maxAge;

    public OutboxRetryPolicy(@Value("${ticket.mq.outbox-max-attempts:20}") int maxAttempts,
                             @Value("${ticket.mq.outbox-max-age:24h}") Duration maxAge) {
        if (maxAttempts < 1 || maxAge.isNegative() || maxAge.isZero())
            throw new IllegalArgumentException("Outbox自动重试预算必须大于零");
        this.maxAttempts = maxAttempts;
        this.maxAge = maxAge;
    }

    /** 载荷错误需人工处理；连接失败、超时和路由故障在预算内继续重试。 */
    public String reviewReason(OutboxEvent event, Exception failure, LocalDateTime now) {
        if (failure instanceof IllegalArgumentException) return "INVALID_PAYLOAD";
        if (failure instanceof org.springframework.amqp.AmqpAuthenticationException) return "BROKER_AUTHENTICATION";
        if (event.getCycleAttemptCount() >= maxAttempts) return "ATTEMPT_BUDGET_EXHAUSTED";
        // 人工重放后使用新一轮的开始时间；总尝试次数和原创建时间始终保留。
        LocalDateTime since = event.getCycleStartedAt() == null ? event.getCreatedAt() : event.getCycleStartedAt();
        if (!now.isBefore(since.plus(maxAge))) return "AGE_BUDGET_EXHAUSTED";
        return null;
    }
}
