package com.byy.ticket.payment.dto.payment;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单服务创建支付单的内部请求；用户不能通过公共接口自行指定金额。
 * @param orderNo 已持久化的 32 位订单编号。
 * @param userId 订单所属用户。
 * @param amount 订单总金额快照，两位以内小数。
 * @param expiresAt 原订单到期时间，精度不超过毫秒。
 */
public record PaymentCreateDTO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{32}") String orderNo,
        @NotNull @Min(1) Long userId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @NotNull LocalDateTime expiresAt) { }
