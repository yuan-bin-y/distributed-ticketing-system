package com.byy.ticket.order.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.review.*;
import com.byy.ticket.order.web.OrderIdentityResolver;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 管理员核对入口，复用 /api/orders/admin/** 的角色校验。 */
@RestController
@RequestMapping("/api/orders/admin/reviews")
public class OrderReviewController {
    private final OrderReviewService service;
    private final OrderReviewMapper actions;
    private final OrderReviewTransaction transactions;
    private final OrderIdentityResolver identity;

    public OrderReviewController(
            OrderReviewService service,
            OrderReviewMapper actions,
            OrderReviewTransaction transactions,
            OrderIdentityResolver identity) {
        this.service = service;
        this.actions = actions;
        this.transactions = transactions;
        this.identity = identity;
    }

    @GetMapping
    public Result<?> list(
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("分页参数无效");
        return Result.success(actions.orders(after, limit));
    }

    @GetMapping("/actions/{actionId}")
    public Result<?> action(@PathVariable String actionId) {
        OrderReviewService.validate(actionId);
        var action = actions.get(actionId);
        if (action == null)
            throw new com.byy.ticket.common.exception.ResourceNotFoundException("处理记录不存在");
        return Result.success(action);
    }

    @GetMapping("/{orderNo}")
    public Result<?> detail(@PathVariable String orderNo) {
        return Result.success(service.detail(orderNo));
    }

    public record Request(
            String idempotencyKey, OrderReviewDecision.Intent action, String reason) {}

    @PostMapping("/{orderNo}/actions")
    public ResponseEntity<?> submit(
            @PathVariable String orderNo, @RequestBody Request body, HttpServletRequest request) {
        OrderReviewService.validate(orderNo);
        return ResponseEntity.accepted()
                .body(
                        Result.success(
                                transactions.submit(
                                        orderNo,
                                        identity.requireUser(request),
                                        body.idempotencyKey(),
                                        body.action(),
                                        body.reason())));
    }
}
