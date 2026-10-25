package com.byy.ticket.order.review;

import static org.junit.jupiter.api.Assertions.*;

import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.model.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

class OrderReviewDecisionTest {
    final LocalDateTime now = LocalDateTime.of(2026, 10, 25, 12, 0);
    final TicketOrder order = order();
    final OrderItem item = item();

    TicketOrder order() {
        var value = new TicketOrder();
        value.setOrderNo("a".repeat(32));
        value.setUserId(1L);
        value.setTotalAmount(BigDecimal.TEN);
        value.setExpiresAt(now.minusMinutes(1));
        value.setQuotaStatus("HELD");
        return value;
    }

    OrderItem item() {
        var value = new OrderItem();
        value.setSessionId(2L);
        value.setTicketTierId(3L);
        value.setQuantity(1);
        return value;
    }

    PaymentResponse payment(boolean reversed) {
        return new PaymentResponse(
                "b".repeat(32),
                order.getOrderNo(),
                1L,
                BigDecimal.TEN,
                "SUCCESS",
                order.getExpiresAt(),
                order.getExpiresAt().minusSeconds(1),
                "PENDING",
                reversed ? "c".repeat(32) : null,
                reversed ? "SUCCESS" : null,
                now.minusMinutes(5));
    }

    StockReservationResponse stock(String status) {
        return new StockReservationResponse(
                "d".repeat(32), order.getOrderNo(), 2L, 3L, 1, order.getExpiresAt(), status);
    }

    OrderStatus decide(
            OrderReviewDecision.Intent intent,
            PaymentResponse payment,
            StockReservationResponse stock) {
        return OrderReviewDecision.decide(
                intent, order, item, new OrderReviewDecision.Facts(payment, stock), now);
    }

    @Test
    void paidAndSoldResumesFulfillment() {
        assertEquals(
                OrderStatus.PAYMENT_CONFIRMING,
                decide(OrderReviewDecision.Intent.RESUME, payment(false), stock("SOLD")));
    }

    @Test
    void paidAndReleasedSchedulesOriginalReversal() {
        assertEquals(
                OrderStatus.PAYMENT_CONFIRMING,
                decide(OrderReviewDecision.Intent.REVERSE, payment(false), stock("RELEASED")));
    }

    @Test
    void paidCannotBeClosedOrResoldAfterRelease() {
        assertThrows(
                OrderConflictException.class,
                () -> decide(OrderReviewDecision.Intent.CLOSE, payment(false), stock("RESERVED")));
        assertThrows(
                OrderConflictException.class,
                () -> decide(OrderReviewDecision.Intent.RESUME, payment(false), stock("RELEASED")));
    }

    @Test
    void soldAndReversedStaysForManualEvidence() {
        assertThrows(
                OrderConflictException.class,
                () -> decide(OrderReviewDecision.Intent.RECONCILE, payment(true), stock("SOLD")));
    }

    @Test
    void expiredUnpaidCanCloseButSoldCannot() {
        assertEquals(
                OrderStatus.CLOSING,
                decide(OrderReviewDecision.Intent.CLOSE, null, stock("RESERVED")));
        assertEquals(OrderStatus.CLOSED, decide(OrderReviewDecision.Intent.CLOSE, null, null));
        assertThrows(
                OrderConflictException.class,
                () -> decide(OrderReviewDecision.Intent.CLOSE, null, stock("SOLD")));
    }

    @Test
    void missingKnownReservationAndPaymentEvidenceAreNotTreatedAsAbsence() {
        order.setReservationId("d".repeat(32));
        assertThrows(
                OrderConflictException.class,
                () -> decide(OrderReviewDecision.Intent.CLOSE, null, null));
        order.setPaymentNo("b".repeat(32));
        order.setPaidAt(now.minusMinutes(2));
        assertThrows(
                OrderConflictException.class,
                () -> decide(OrderReviewDecision.Intent.CLOSE, null, stock("RESERVED")));
    }

    @Test
    void futureUnpaidReservationCanResumeButCannotClose() {
        order.setExpiresAt(now.plusMinutes(5));
        assertEquals(
                OrderStatus.PENDING_PAYMENT,
                decide(OrderReviewDecision.Intent.RESUME, null, stock("RESERVED")));
        assertThrows(
                OrderConflictException.class,
                () -> decide(OrderReviewDecision.Intent.CLOSE, null, stock("RESERVED")));
    }

    @Test
    void releasedQuotaCannotResumeSelling() {
        order.setQuotaStatus("RELEASED");
        assertThrows(
                OrderConflictException.class,
                () -> decide(OrderReviewDecision.Intent.RESUME, payment(false), stock("SOLD")));
    }
}
