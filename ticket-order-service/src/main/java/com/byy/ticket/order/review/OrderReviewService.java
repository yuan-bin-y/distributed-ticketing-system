package com.byy.ticket.order.review;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.seat.SeatSelection;
import com.byy.ticket.order.client.*;
import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.mapper.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

/** 人工处置沿用原订单、付款和预留编号。未知结果继续重试，管理员可查询每次操作的依据。 */
@Service
public class OrderReviewService {
    private final OrderReviewMapper actions;
    private final OrderReviewTransaction transaction;
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final InventoryClient inventory;
    private final PaymentClient payments;
    private final StockAdmissionClient admissions;
    private final ObjectMapper json;
    private final Clock clock;

    public OrderReviewService(
            OrderReviewMapper actions,
            OrderReviewTransaction transaction,
            OrderMapper orders,
            OrderItemMapper items,
            InventoryClient inventory,
            PaymentClient payments,
            StockAdmissionClient admissions,
            ObjectMapper json,
            Clock clock) {
        this.actions = actions;
        this.transaction = transaction;
        this.orders = orders;
        this.items = items;
        this.inventory = inventory;
        this.payments = payments;
        this.admissions = admissions;
        this.json = json;
        this.clock = clock;
    }

    public record Detail(
            com.byy.ticket.order.model.TicketOrder order,
            com.byy.ticket.order.model.OrderItem item,
            OrderReviewDecision.Facts facts,
            String factError,
            List<OrderReviewMapper.Action> actions) {}

    public Detail detail(String orderNo) {
        validate(orderNo);
        var order =
                orders.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                                        com.byy.ticket.order.model.TicketOrder>()
                                .eq("order_no", orderNo));
        if (order == null) throw new ResourceNotFoundException("订单不存在");
        OrderReviewDecision.Facts facts = null;
        String error = null;
        try {
            facts = facts(orderNo);
        } catch (RuntimeException unavailable) {
            error = unavailable.getClass().getSimpleName();
        }
        return new Detail(
                order,
                items.selectByOrderId(order.getId()),
                facts,
                error,
                actions.history(order.getId()));
    }

    public OrderReviewDecision.Facts facts(String orderNo) {
        PaymentResponse payment;
        StockReservationResponse stock;
        try {
            payment = payments.getByOrder(orderNo);
        } catch (ResourceNotFoundException missing) {
            payment = null;
        }
        try {
            stock = inventory.getByOrder(orderNo);
        } catch (ResourceNotFoundException missing) {
            stock = null;
        }
        return new OrderReviewDecision.Facts(payment, stock);
    }

    @Scheduled(
            scheduler = "orderReviewScheduler",
            fixedDelayString = "${ticket.order.review-delay-ms:3000}")
    public void recover() {
        for (String id : actions.due()) {
            try {
                advance(id);
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                        .warn(
                                "人工处置等待接管 actionId={} cause={}",
                                id,
                                failure.getClass().getSimpleName());
            }
        }
    }

    public void advance(String id) {
        String token = UUID.randomUUID().toString().replace("-", "");
        var order = transaction.claim(id, token);
        if (order == null) return;
        String evidence = null;
        try {
            var action = actions.get(id);
            var item = items.selectByOrderId(order.getId());
            var facts = facts(order.getOrderNo());
            evidence = json.writeValueAsString(facts);
            var target =
                    OrderReviewDecision.decide(
                            OrderReviewDecision.Intent.valueOf(action.action()),
                            order,
                            item,
                            facts,
                            LocalDateTime.now(clock));
            boolean cancelled = false;
            if (target == com.byy.ticket.order.model.OrderStatus.CLOSED) {
                var result =
                        admissions.cancel(
                                new StockReservationRequest(
                                        order.getOrderNo(),
                                        item.getSessionId(),
                                        item.getTicketTierId(),
                                        item.getQuantity(),
                                        order.getExpiresAt(),
                                        SeatSelection.decode(item.getSeatIds())));
                cancelled = Set.of("CANCELLED", "RELEASED").contains(result.status());
            }
            transaction.resolve(id, token, target, facts, evidence, cancelled);
        } catch (OrderConflictException | IllegalArgumentException invalid) {
            transaction.failed(id, token, evidence, invalid.getMessage(), true);
        } catch (RuntimeException unavailable) {
            transaction.failed(id, token, evidence, unavailable.getClass().getSimpleName(), false);
        }
    }

    public static void validate(String number) {
        if (number == null || !number.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("订单或操作编号无效");
    }
}
