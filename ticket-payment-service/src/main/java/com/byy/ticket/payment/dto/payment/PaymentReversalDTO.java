package com.byy.ticket.payment.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 订单服务决定无法履约后提交的全额冲正命令，金额从支付单读取。
 * @param paymentNo 原支付单编号。
 * @param reason 冲正原因，同一支付单重复请求应使用相同原因。
 */
public record PaymentReversalDTO(
        @NotBlank @Pattern(regexp = "[0-9a-f]{32}") String paymentNo,
        @NotBlank @Size(max = 256) String reason) { }
