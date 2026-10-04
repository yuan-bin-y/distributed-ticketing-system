package com.byy.ticket.order.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单侧拥有的远程请求契约，不引用支付服务的Java模块。
 * @param orderNo 本地已保存的订单编号，也是支付创建幂等依据。
 * @param userId 原订单用户。
 * @param amount 原订单总金额。
 * @param expiresAt 原订单到期时间，重试不能延长。
 */
public record PaymentCreateRequest(String orderNo, Long userId, BigDecimal amount, LocalDateTime expiresAt) { }
