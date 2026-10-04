package com.byy.ticket.order.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付冲正 HTTP 契约，订单侧独立定义并核对归属、金额及完成时间。 */
public record PaymentReversalResponse(String reversalNo, String paymentNo, String orderNo,
        BigDecimal amount, String reason, String status, LocalDateTime completedAt, LocalDateTime createdAt) { }
