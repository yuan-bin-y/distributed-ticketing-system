package com.byy.ticket.order.client.dto;

import java.time.LocalDateTime;

/**
 * 订单侧定义的库存响应 JSON 接收对象，不依赖库存服务的 Maven 模块。
 * 用于核对实际返回的预留编号、原请求参数和状态。
 * 
 * @param reservationId 库存服务生成的 32 位预留编号。
 * @param orderId 调用方提供的订单编号，库存预留用它识别重复请求。
 * @param sessionId 所属场次 ID，跨服务传递时作为业务标识。
 * @param ticketTierId 票档 ID，用于关联规则与库存。
 * @param quantity 本次购买或预留的票数。
 * @param expiresAt 预留到期时间，按 UTC+8 本地时间传递且精度不超过毫秒；当前尚无自动过期释放任务。
 * @param status 预留状态：RESERVED、SOLD 或 RELEASED。
 */
public record StockReservationResponse(String reservationId, String orderId, Long sessionId,
                                       Long ticketTierId, Integer quantity, LocalDateTime expiresAt, String status) {
}
