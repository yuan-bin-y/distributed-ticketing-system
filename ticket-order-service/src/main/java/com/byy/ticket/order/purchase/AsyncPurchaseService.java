package com.byy.ticket.order.purchase;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.observability.TraceSupport;
import com.byy.ticket.order.client.*;
import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.config.OrderWorkflowProperties;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.mapper.OrderMapper;
import com.byy.ticket.order.rules.PurchaseRuleEngine;
import com.byy.ticket.order.service.PurchaseRequestId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** 异步受理仅承诺意图已保存；是否建单成功必须查询状态，不能把202当成购票成功。 */
@Service
public class AsyncPurchaseService {
    public record Accepted(
            String requestId,
            String status,
            String orderNo,
            LocalDateTime expiresAt,
            String message) {}

    private final PurchaseAcceptanceMapper acceptance;
    private final PurchaseAcceptanceTransaction transaction;
    private final EventCallGuard events;
    private final StockAdmissionClient inventory;
    private final PurchaseRuleEngine rules;
    private final Clock clock;
    private final OrderWorkflowProperties settings;
    private final ObjectMapper json;
    private final TraceSupport traces;
    private final OrderMapper orders;
    private final boolean mq;

    public AsyncPurchaseService(
            PurchaseAcceptanceMapper acceptance,
            PurchaseAcceptanceTransaction transaction,
            EventCallGuard events,
            StockAdmissionClient inventory,
            PurchaseRuleEngine rules,
            Clock clock,
            OrderWorkflowProperties settings,
            ObjectMapper json,
            TraceSupport traces,
            OrderMapper orders,
            @Value("${ticket.mq.enabled:false}") boolean mq) {
        this.acceptance = acceptance;
        this.transaction = transaction;
        this.events = events;
        this.inventory = inventory;
        this.rules = rules;
        this.clock = clock;
        this.settings = settings;
        this.json = json;
        this.traces = traces;
        this.orders = orders;
        this.mq = mq;
    }

    public Accepted accept(long user, OrderCreateDTO request) {
        if (!mq || !inventory.enabled())
            throw new OrderConflictException("异步下单需要同时启用 MQ 与 Redis 库存预扣");
        if (user < 1
                || request == null
                || request.ticketTierId() == null
                || request.ticketTierId() < 1
                || request.quantity() == null
                || request.quantity() < 1
                || request.idempotencyKey() == null
                || !request.idempotencyKey().matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("受理参数无效");
        var old = acceptance.byKey(user, request.idempotencyKey());
        if (old != null) {
            if (!request.equals(json.readValue(old.getRequestJson(), OrderCreateDTO.class)))
                throw new OrderConflictException("相同幂等键的购买参数不一致");
            return view(old);
        }
        inventory.requireCompatible();
        var rule = events.getPurchaseRule(request.ticketTierId());
        var now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        rules.checkRequest(request.quantity(), rule, now);
        BigDecimal amount =
                com.byy.ticket.order.service.PurchaseSnapshot.amount(rule, request.quantity());
        String id = PurchaseRequestId.of(user, request.idempotencyKey());
        var held =
                inventory.hold(
                        new StockReservationRequest(
                                id,
                                rule.sessionId(),
                                rule.ticketTierId(),
                                request.quantity(),
                                now.plus(settings.paymentWindow()),
                                request.seatIds()));
        if (!java.util.Set.of("HELD", "APPLIED").contains(held.status()))
            throw new OrderConflictException("库存预扣已结束");
        var candidate = new PurchaseAcceptance();
        candidate.setRequestId(id);
        candidate.setUserId(user);
        candidate.setIdempotencyKey(request.idempotencyKey());
        candidate.setTicketTierId(request.ticketTierId());
        candidate.setQuantity(request.quantity());
        candidate.setRequestJson(json.writeValueAsString(request));
        candidate.setRuleJson(json.writeValueAsString(rule));
        candidate.setAmount(amount);
        candidate.setExpiresAt(held.expiresAt());
        candidate.setAcceptedAt(now);
        candidate.setStatus("ACCEPTED");
        candidate.setAttemptCount(0);
        candidate.setNextAttemptAt(now.plusSeconds(30));
        var context = traces.capture();
        candidate.setTraceParent(context.traceParent());
        candidate.setTraceState(context.traceState());
        return view(transaction.accept(candidate));
    }

    public Accepted get(long user, String id) {
        if (id == null || !id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("受理编号无效");
        var item = acceptance.selectById(id);
        if (item == null || item.getUserId() != user)
            throw new ResourceNotFoundException("受理记录不存在");
        return view(item);
    }

    private Accepted view(PurchaseAcceptance item) {
        var order = orders.selectByRequest(item.getUserId(), item.getIdempotencyKey());
        return new Accepted(
                item.getRequestId(),
                item.getStatus(),
                order == null ? null : order.getOrderNo(),
                item.getExpiresAt(),
                item.getLastError());
    }
}
