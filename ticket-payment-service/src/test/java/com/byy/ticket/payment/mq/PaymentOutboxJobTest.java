package com.byy.ticket.payment.mq;

import com.byy.ticket.payment.mapper.OutboxEventMapper;
import com.byy.ticket.payment.model.OutboxEvent;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentOutboxJobTest {
    final OutboxEventMapper events=mock(OutboxEventMapper.class);
    final PaymentEventPublisher publisher=mock(PaymentEventPublisher.class);
    final OutboxMessageValidator validator=mock(OutboxMessageValidator.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"),ZoneOffset.ofHours(8));
    final PaymentOutboxJob job=new PaymentOutboxJob(events,publisher,new OutboxRetryPolicy(3,Duration.ofHours(1)),validator,clock);
    OutboxEvent fixture(int attempts) {
        var event=new OutboxEvent();event.setId(1L);event.setEventId("a".repeat(32));event.setCycleAttemptCount(attempts);
        event.setCreatedAt(LocalDateTime.now(clock));
        when(events.selectDue()).thenReturn(List.of(event));when(events.claim(eq(1L),anyString())).thenReturn(1);
        when(events.selectById(1L)).thenReturn(event);return event;
    }
    @Test void lostPublishProgressRepublishesOriginalIdentity() throws Exception {
        var event=fixture(2);job.publishDue();job.publishDue();
        verify(publisher,times(2)).publish(same(event));verify(events,times(2)).published(eq(1L),anyString());
    }
    @Test void transientFailureRetriesWithoutMarkingPublished() throws Exception {
        fixture(1);doThrow(new java.net.SocketTimeoutException()).when(publisher).publish(any());job.publishDue();
        verify(events).retry(eq(1L),anyString(),eq("SocketTimeoutException"));verify(events,never()).published(anyLong(),anyString());
    }
    @Test void exhaustionMovesToReviewInsteadOfRetryingForever() throws Exception {
        fixture(3);doThrow(new java.io.IOException()).when(publisher).publish(any());job.publishDue();
        verify(events).review(eq(1L),anyString(),eq("ATTEMPT_BUDGET_EXHAUSTED"),eq("IOException"));
        verify(events,never()).retry(anyLong(),anyString(),anyString());
    }
    @Test void invalidPayloadIsNeverPublished() throws Exception {
        fixture(1);doThrow(new IllegalArgumentException()).when(validator).validate(any());job.publishDue();
        verifyNoInteractions(publisher);verify(events).review(eq(1L),anyString(),eq("INVALID_PAYLOAD"),anyString());
    }
    @Test void losingClaimDoesNotPublish() throws Exception {
        fixture(1);when(events.claim(eq(1L),anyString())).thenReturn(0);job.publishDue();verifyNoInteractions(publisher);
    }
}
