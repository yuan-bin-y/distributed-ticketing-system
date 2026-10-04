package com.byy.ticket.order.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付服务返回的支付事实，订单侧独立定义HTTP契约。
 * @param paymentNo 支付编号。
 * @param orderNo 所属订单编号。
 * @param userId 原订单用户。
 * @param amount 支付金额快照。
 * @param status CREATED或SUCCESS，不表示订单已经履约。
 * @param expiresAt 原支付期限。
 * @param paidAt 首次付款成功时间。
 * @param notifyStatus 支付结果通知进度。
 * @param reversalNo 可为空的冲正编号。
 * @param reversalStatus 可为空的冲正状态。
 * @param createdAt 支付单创建时间。
 */
public record PaymentResponse(String paymentNo, String orderNo, Long userId, BigDecimal amount,
                              String status, LocalDateTime expiresAt, LocalDateTime paidAt,
                              String notifyStatus, String reversalNo, String reversalStatus,
                              LocalDateTime createdAt) { }
