package com.byy.ticket.order.dto.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 购票预览请求，价格与规则由后端获取。 */
public record OrderPreviewDTO(
        @NotNull(message = "票档ID不能为空") @Min(value = 1, message = "票档ID必须大于零") Long ticketTierId,
        @NotNull(message = "购买数量不能为空") @Min(value = 1, message = "购买数量必须大于零") Integer quantity
) {
}
