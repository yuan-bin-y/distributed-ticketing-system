package com.byy.ticket.order.review;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.mapper.*;
import com.byy.ticket.order.model.*;
import com.byy.ticket.order.service.OrderTransactionService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.UUID;

/** 只执行本库 SQL；处理命令、状态恢复与审计一起提交，网络请求在外层执行。 */
@Service
public class OrderReviewTransaction {
    private final OrderMapper orders;
    private final OrderReviewMapper actions;
    private final OrderTransactionService transactions;
    private final Clock clock;

    public OrderReviewTransaction(
            OrderMapper orders,
            OrderReviewMapper actions,
            OrderTransactionService transactions,
            Clock clock) {
        this.orders = orders;
        this.actions = actions;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional
    public OrderReviewMapper.Action submit(
            String orderNo,
            long operator,
            String key,
            OrderReviewDecision.Intent intent,
            String reason) {
        if (operator < 1
                || key == null
                || !key.matches("[A-Za-z0-9_-]{1,64}")
                || intent == null
                || reason == null
                || reason.isBlank()
                || reason.length() > 200)
            throw new IllegalArgumentException("请填写有效操作编号、处理方向和1至200字原因");
        var order = orders.selectByNoForUpdate(orderNo);
        if (order == null) throw new ResourceNotFoundException("订单不存在");
        var old = actions.byKey(order.getId(), key);
        if (old != null) {
            if (old.operatorId() != operator
                    || !old.action().equals(intent.name())
                    || !old.reason().equals(reason))
                throw new OrderConflictException("同一操作编号必须保持原操作人、方向和原因");
            return old;
        }
        if (!"REVIEW_REQUIRED".equals(order.getStatus()))
            throw new OrderConflictException("订单当前不在待核对状态");
        String id = UUID.randomUUID().toString().replace("-", "");
        actions.insert(id, order.getId(), key, operator, intent.name(), reason);
        return actions.get(id);
    }

    /** 固定先锁订单、再锁命令。命令中途宕机后，租约到期可以接管同一操作。 */
    @Transactional
    public TicketOrder claim(String id, String token) {
        var snapshot = actions.get(id);
        if (snapshot == null) return null;
        var order = orders.selectByIdForUpdate(snapshot.orderId());
        var action = actions.lock(id);
        if (!java.util.Set.of("PENDING", "RUNNING").contains(action.status())
                || action.nextAttemptAt().isAfter(now())) return null;
        if (!"REVIEW_REQUIRED".equals(order.getStatus())) {
            actions.finish(id, "SUPERSEDED", null, order.getStatus(), "订单已由其他流程推进", 0);
            return null;
        }
        if (actions.claim(order.getId(), token) != 1) return null;
        actions.running(id, token);
        order.setLeaseToken(token);
        return order;
    }

    @Transactional
    public void resolve(
            String id,
            String token,
            OrderStatus target,
            OrderReviewDecision.Facts facts,
            String factsJson,
            boolean cancellationConfirmed) {
        var action = actions.get(id);
        var order = orders.selectByIdForUpdate(action.orderId());
        var current = actions.lock(id);
        if (!"RUNNING".equals(current.status()) || !token.equals(current.leaseToken())) return;
        if (!matches(order, token)) {
            actions.finish(id, "SUPERSEDED", factsJson, order.getStatus(), "核对期间订单已被其他流程推进", 0);
            return;
        }
        var stock = facts.stock();
        String reservation = stock == null ? order.getReservationId() : stock.reservationId();
        if (target == OrderStatus.CLOSED) {
            if (!cancellationConfirmed) throw new OrderConflictException("缺少持久化预扣取消依据");
            if (transactions.finishAndReleaseQuota(
                            order.getId(),
                            token,
                            "REVIEW_REQUIRED",
                            target,
                            reservation,
                            now(),
                            null)
                    != 1) throw new OrderConflictException("订单已被其他操作接管");
        } else if (target == OrderStatus.PAYMENT_CONFIRMING) {
            var payment = facts.payment();
            if (actions.reservation(order.getId(), token, reservation) != 1)
                throw new OrderConflictException("订单领取已失效");
            orders.savePayment(
                    order.getId(), payment.paymentNo(), payment.paidAt(), target.name(), null);
        } else {
            if (orders.finish(
                            order.getId(),
                            token,
                            "REVIEW_REQUIRED",
                            target.name(),
                            reservation,
                            now(),
                            target == OrderStatus.REVIEW_REQUIRED ? order.getLastError() : null)
                    != 1) throw new OrderConflictException("订单领取已失效");
        }
        actions.finish(id, "DONE", factsJson, target.name(), null, 0);
    }

    @Transactional
    public void failed(String id, String token, String factsJson, String error, boolean permanent) {
        var snapshot = actions.get(id);
        var order = orders.selectByIdForUpdate(snapshot.orderId());
        var action = actions.lock(id);
        if (!"RUNNING".equals(action.status()) || !token.equals(action.leaseToken())) return;
        if (!matches(order, token)) {
            // 新领取者仍在运行时，旧线程不得覆盖命令审计。
            if (!"REVIEW_REQUIRED".equals(order.getStatus()))
                actions.finish(id, "SUPERSEDED", factsJson, order.getStatus(), "订单状态已变化", 0);
            return;
        }
        orders.finish(
                order.getId(),
                token,
                "REVIEW_REQUIRED",
                "REVIEW_REQUIRED",
                order.getReservationId(),
                now(),
                order.getLastError());
        boolean rejected = permanent || action.attemptCount() >= 20;
        actions.finish(
                id,
                rejected ? "REJECTED" : "PENDING",
                factsJson,
                order.getStatus(),
                error,
                Math.min(300, 1 << Math.min(action.attemptCount(), 8)));
    }

    private boolean matches(TicketOrder order, String token) {
        return order != null
                && "REVIEW_REQUIRED".equals(order.getStatus())
                && token.equals(order.getLeaseToken());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
