package com.byy.ticket.order.dto.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;

/**
 * 订单服务内部预留入口的请求参数，调用方提供稳定的订单编号和到期时间。
 * 
 * @param orderId 调用方提供的订单编号，库存预留用它识别重复请求。
 * @param sessionId 所属场次 ID，跨服务传递时作为业务标识。
 * @param ticketTierId 票档 ID，用于关联规则与库存。
 * @param quantity 本次购买或预留的票数。
 * @param expiresAt 预留到期时间，按 UTC+8 本地时间传递且精度不超过毫秒；当前尚无自动过期释放任务。
 */
public record OrderStockReserveDTO(
        @NotBlank(message = "订单关联编号不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "订单关联编号格式不正确") String orderId,
        @NotNull(message = "场次ID不能为空") @Min(value = 1, message = "场次ID必须大于零") Long sessionId,
        @NotNull(message = "票档ID不能为空") @Min(value = 1, message = "票档ID必须大于零") Long ticketTierId,
        @NotNull(message = "数量不能为空") @Min(value = 1, message = "数量必须大于零") Integer quantity,
        @NotNull(message = "到期时间不能为空") LocalDateTime expiresAt) {
}
