package com.byy.ticket.order.dto.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 用户下单参数；金额、用户身份、订单编号和到期时间由服务端确定。
 * @param ticketTierId 购买的票档 ID。
 * @param quantity 购买数量。
 * @param idempotencyKey 标识一次购买，重复提交时必须保持相同且参数一致。
 */
public record OrderCreateDTO(
        @NotNull @Min(1) Long ticketTierId,
        @NotNull @Min(1) Integer quantity,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey) {
}
