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
    public PaymentOutboxJob(OutboxEventMapper events, PaymentEventPublisher publisher) {
        this.events=events; this.publisher=publisher;
    }
    @Scheduled(fixedDelayString="${ticket.mq.outbox-delay-ms:1000}")
    public void publishDue() {
        for (var candidate: events.selectDue()) {
            String token=UUID.randomUUID().toString().replace("-","");
            if(events.claim(candidate.getId(),token)!=1) continue;
            var event=events.selectById(candidate.getId());
            try {
                publisher.publish(event);
                events.published(event.getId(),token);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                String error=failure.getClass().getSimpleName()+": "+failure.getMessage();
                events.retry(event.getId(),token,error.substring(0,Math.min(error.length(),1000)));
                LoggerFactory.getLogger(getClass()).warn("支付事件发送失败 eventId={}",event.getEventId());
            }
        }
    }
}
