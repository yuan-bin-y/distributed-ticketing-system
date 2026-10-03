package com.byy.ticket.order.dto.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 订单预览请求，Bean Validation 要求票档 ID 和数量都大于零。
 * 
 * @param ticketTierId 票档 ID，用于关联规则与库存。
 * @param quantity 本次购买或预留的票数。
 */
public record OrderPreviewDTO(
        @NotNull(message = "票档ID不能为空") @Min(value = 1, message = "票档ID必须大于零") Long ticketTierId,
        @NotNull(message = "购买数量不能为空") @Min(value = 1, message = "购买数量必须大于零") Integer quantity
) {
}
