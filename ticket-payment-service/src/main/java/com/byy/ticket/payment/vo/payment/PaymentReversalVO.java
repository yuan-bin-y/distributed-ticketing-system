package com.byy.ticket.payment.vo.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟冲正结果；SUCCESS 仅代表本项目模拟操作完成。
 * @param reversalNo 冲正编号。
 * @param paymentNo 原支付编号。
 * @param orderNo 原订单编号。
 * @param amount 从支付单读取的全额金额。
 * @param reason 原冲正原因。
 * @param status 冲正状态。
 * @param completedAt 完成时间。
 * @param createdAt 创建时间。
 */
public record PaymentReversalVO(String reversalNo, String paymentNo, String orderNo,
                                BigDecimal amount, String reason, String status,
                                LocalDateTime completedAt, LocalDateTime createdAt) { }
