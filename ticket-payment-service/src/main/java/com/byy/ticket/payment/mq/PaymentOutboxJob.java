package com.byy.ticket.payment.mq;

import com.byy.ticket.payment.mapper.OutboxEventMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import java.util.UUID;

/** 短SQL领取任务，网络发送在事务外；租约编号阻止过期发送器覆盖新进度。 */
@Component
@ConditionalOnProperty(name="ticket.mq.enabled", havingValue="true")
public class PaymentOutboxJob {
    private final OutboxEventMapper events;
    private final PaymentEventPublisher publisher;
    private final OutboxRetryPolicy policy;
    private final OutboxMessageValidator validator;
    private final java.time.Clock clock;
    public PaymentOutboxJob(OutboxEventMapper events, PaymentEventPublisher publisher,
                            OutboxRetryPolicy policy, OutboxMessageValidator validator, java.time.Clock clock) {
        this.events=events; this.publisher=publisher; this.policy=policy; this.validator=validator; this.clock=clock;
    }
    @Scheduled(fixedDelayString="${ticket.mq.outbox-delay-ms:1000}")
    public void publishDue() {
        for (var candidate: events.selectDue()) {
            String token=UUID.randomUUID().toString().replace("-","");
            if(events.claim(candidate.getId(),token)!=1) continue;
            var event=events.selectById(candidate.getId());
            try {
                validator.validate(event);
                publisher.publish(event);
                events.published(event.getId(),token);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                String reason=policy.reviewReason(event,failure,java.time.LocalDateTime.now(clock));
                // 不把异常消息中的连接串或凭证复制到数据库和日志。
                String error=failure.getClass().getSimpleName();
                if(reason==null) events.retry(event.getId(),token,error);
                else if(events.review(event.getId(),token,reason,error)==1)
                    LoggerFactory.getLogger(getClass()).error("支付事件待人工核对 eventId={} reason={}",event.getEventId(),reason);
                if (Thread.currentThread().isInterrupted()) return;
            }
        }
    }
}
