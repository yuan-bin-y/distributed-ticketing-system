package com.byy.ticket.order.mq;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentForwardingBackoffTest {
    @Test void outageSlowsRepeatedDeliveriesAndRemainsBounded() {
        var metrics=new SimpleMeterRegistry();
        try {
            var backoff=new PaymentForwardingBackoff(Duration.ofSeconds(1),Duration.ofSeconds(30),metrics);
            long previous=0;
            for(int i=0;i<100;i++) {
                long delay=backoff.failureDelayMillis();
                assertTrue(delay>=previous && delay<=30000);previous=delay;
            }
            assertEquals(30000,previous);
            assertEquals(100,metrics.counter("ticket.mq.forwarding.failures").count());
            assertEquals(30,metrics.get("ticket.mq.forwarding.consecutive.failures").gauge().value());
        } finally {metrics.close();}
    }
    @Test void RedeliveryDoesNotResetFailureBudgetButConfirmedForwardDoes() {
        var metrics=new SimpleMeterRegistry();
        try {
            var backoff=new PaymentForwardingBackoff(Duration.ofSeconds(1),Duration.ofSeconds(30),metrics);
            backoff.failureDelayMillis();backoff.delivered(true);
            assertTrue(backoff.failureDelayMillis()>=2000);
            backoff.recovered();assertTrue(backoff.failureDelayMillis()<1200);
            assertEquals(1,metrics.counter("ticket.mq.deliveries","redelivered","true").count());
        } finally {metrics.close();}
    }
    @Test void rejectsUnboundedOrInvalidWaits() {
        var metrics=new SimpleMeterRegistry();
        try {
            assertThrows(IllegalArgumentException.class,()->new PaymentForwardingBackoff(Duration.ZERO,Duration.ofSeconds(1),metrics));
            assertThrows(IllegalArgumentException.class,()->new PaymentForwardingBackoff(Duration.ofSeconds(2),Duration.ofSeconds(1),metrics));
            assertThrows(IllegalArgumentException.class,()->new PaymentForwardingBackoff(Duration.ofSeconds(1),Duration.ofHours(1),metrics));
        } finally {metrics.close();}
    }
}
