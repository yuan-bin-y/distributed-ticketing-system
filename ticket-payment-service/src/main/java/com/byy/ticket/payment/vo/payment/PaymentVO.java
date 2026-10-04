package com.byy.ticket.payment.vo.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付事实及冲正结果，供调用方核对；notifyStatus 不表示订单已经完成。
 * @param paymentNo 支付编号。
 * @param orderNo 订单编号。
 * @param userId 所属用户。
 * @param amount 金额快照。
 * @param status 支付事实状态。
 * @param expiresAt 支付期限。
 * @param paidAt 首次支付成功时间。
 * @param notifyStatus 通知处理状态。
 * @param reversalNo 若已生成冲正则为其编号。
 * @param reversalStatus 若已生成冲正则为其状态。
 * @param createdAt 创建时间。
 */
public record PaymentVO(String paymentNo, String orderNo, Long userId, BigDecimal amount,
                        String status, LocalDateTime expiresAt, LocalDateTime paidAt,
                        String notifyStatus, String reversalNo, String reversalStatus,
                        LocalDateTime createdAt) { }
