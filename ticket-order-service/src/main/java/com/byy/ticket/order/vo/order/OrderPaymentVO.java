package com.byy.ticket.order.vo.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发起支付返回的支付单事实；paymentStatus不是订单状态，付款闭环尚待接入。
 * @param orderNo 原订单编号。
 * @param paymentNo 稳定支付编号。
 * @param amount 原订单金额。
 * @param paymentStatus CREATED或SUCCESS。
 * @param expiresAt 原支付期限。
 * @param paidAt 支付服务记录的首次成功时间。
 * @param reversalNo 可为空的冲正编号。
 * @param reversalStatus 可为空的冲正结果。
 */
public record OrderPaymentVO(String orderNo, String paymentNo, BigDecimal amount, String paymentStatus,
                             LocalDateTime expiresAt, LocalDateTime paidAt,
                             String reversalNo, String reversalStatus) { }
