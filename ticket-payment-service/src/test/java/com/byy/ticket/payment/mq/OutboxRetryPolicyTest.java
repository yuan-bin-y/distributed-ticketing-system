package com.byy.ticket.payment.mq;

import com.byy.ticket.payment.model.OutboxEvent;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutboxRetryPolicyTest {
    final LocalDateTime now=LocalDateTime.of(2026,1,1,12,0);
    final OutboxRetryPolicy policy=new OutboxRetryPolicy(3,Duration.ofHours(1));
    OutboxEvent event(int attempts) {
        var event=new OutboxEvent(); event.setCycleAttemptCount(attempts); event.setCreatedAt(now.minusMinutes(1)); return event;
    }
    @Test void temporaryFailuresKeepOriginalEventWithinBudget() {
        assertNull(policy.reviewReason(event(1),new java.net.SocketTimeoutException(),now));
    }
    @Test void thresholdAndInvalidPayloadRequireReview() {
        assertEquals("ATTEMPT_BUDGET_EXHAUSTED",policy.reviewReason(event(3),new Exception(),now));
        assertEquals("INVALID_PAYLOAD",policy.reviewReason(event(1),new IllegalArgumentException(),now));
    }
    @Test void ageBudgetAndRedriveCycleAreIndependentFromHistoricalAge() {
        var event=event(1); event.setCreatedAt(now.minusDays(2));
        assertEquals("AGE_BUDGET_EXHAUSTED",policy.reviewReason(event,new Exception(),now));
        event.setCycleStartedAt(now.minusMinutes(1));
        assertNull(policy.reviewReason(event,new Exception(),now));
    }
    @Test void budgetMustBePositive() {
        assertThrows(IllegalArgumentException.class,()->new OutboxRetryPolicy(0,Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class,()->new OutboxRetryPolicy(1,Duration.ZERO));
    }
}
