package com.byy.ticket.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.byy.ticket.order.client.*;
import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.config.OrderWorkflowProperties;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.mapper.*;
import com.byy.ticket.order.model.*;
import com.byy.ticket.order.service.impl.OrderServiceImpl;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.*;

/** 编排边界测试：拒绝请求不落订单；超时与明确回滚采用不同补偿策略。 */
class PreholdOrderTest {
    final EventCallGuard events = mock(EventCallGuard.class);
    final OrderMapper orders = mock(OrderMapper.class);
    final OrderItemMapper items = mock(OrderItemMapper.class);
    final OrderTransactionService transactions = mock(OrderTransactionService.class);
    final OrderStockWorkflow workflow = mock(OrderStockWorkflow.class);
    final StockAdmissionClient admissions = mock(StockAdmissionClient.class);
    final LocalDateTime now = LocalDateTime.of(2026, 10, 24, 12, 0);
    final OrderCreateDTO request = new OrderCreateDTO(3L, 2, "same_request");
    OrderServiceImpl service;

    @BeforeEach
    void setup() {
        service =
                new OrderServiceImpl(
                        events,
                        mock(InventoryClient.class),
                        Clock.fixed(now.toInstant(ZoneOffset.ofHours(8)), ZoneOffset.ofHours(8)),
                        orders,
                        items,
                        transactions,
                        workflow,
                        new OrderWorkflowProperties(false, null, true, null, null, null, null),
                        mock(PaymentClient.class),
                        admissions,
                        mock(com.byy.ticket.order.rules.PurchaseRuleEngine.class));
        when(admissions.enabled()).thenReturn(true);
        when(events.getPurchaseRule(3L))
                .thenReturn(
                        new TicketPurchaseRuleResponse(
                                1L,
                                2L,
                                3L,
                                "test",
                                new BigDecimal("199.00"),
                                now.minusHours(1),
                                now.plusHours(1),
                                2));
        when(admissions.hold(any()))
                .thenReturn(
                        new StockAdmissionClient.Admission(
                                PurchaseRequestId.of(7, "same_request"),
                                3L,
                                2L,
                                2,
                                now.plusMinutes(10),
                                "HELD"));
    }

    @Test
    void soldOutNeverCreatesFormalOrder() {
        when(admissions.hold(any())).thenThrow(new OrderConflictException("sold out"));
        assertThrows(OrderConflictException.class, () -> service.create(7L, request));
        verifyNoInteractions(transactions, workflow);
    }

    @Test
    void preholdComesFirstAndUsesOriginalExpiry() {
        var order = new TicketOrder();
        order.setId(9L);
        order.setOrderNo(PurchaseRequestId.of(7, "same_request"));
        order.setStatus("STOCK_PENDING");
        when(transactions.createAccepted(
                        anyString(),
                        eq(7L),
                        eq(request),
                        any(),
                        any(),
                        eq(now),
                        eq(now.plusMinutes(10))))
                .thenReturn(order);
        when(orders.selectById(9L)).thenReturn(order);
        var item = new OrderItem();
        item.setTicketTierId(3L);
        item.setQuantity(2);
        when(items.selectByOrderId(9L)).thenReturn(item);
        service.create(7L, request);
        var ordered = inOrder(admissions, transactions, workflow);
        ordered.verify(admissions).hold(any());
        ordered.verify(transactions)
                .createAccepted(
                        eq(order.getOrderNo()),
                        eq(7L),
                        eq(request),
                        any(),
                        any(),
                        eq(now),
                        eq(now.plusMinutes(10)));
        ordered.verify(workflow).advance(9L);
    }

    @Test
    void definiteQuotaRollbackCancelsButUnknownCommitDoesNot() {
        when(transactions.createAccepted(anyString(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new OrderConflictException("quota"));
        assertThrows(OrderConflictException.class, () -> service.create(7L, request));
        verify(admissions).cancel(any());
        reset(transactions);
        clearInvocations(admissions);
        when(transactions.createAccepted(anyString(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "commit result unknown"));
        assertThrows(RuntimeException.class, () -> service.create(7L, request));
        verify(admissions, never()).cancel(any());
    }

    @Test
    void stableIdSeparatesUsersAndPreservesRetries() {
        assertEquals(PurchaseRequestId.of(7, "key"), PurchaseRequestId.of(7, "key"));
        assertNotEquals(PurchaseRequestId.of(7, "key"), PurchaseRequestId.of(8, "key"));
    }
}
