package com.byy.ticket.order.client.dto;

import java.time.LocalDateTime;

/**
 * 订单侧定义的库存预留 HTTP 请求对象，由 JSON 序列化后发给库存服务。
 * 重试同一预留时应使用相同订单编号、场次、票档、数量和到期时间。
 * 
 * @param orderId 调用方提供的订单编号，库存预留用它识别重复请求。
 * @param sessionId 所属场次 ID，跨服务传递时作为业务标识。
 * @param ticketTierId 票档 ID，用于关联规则与库存。
 * @param quantity 本次购买或预留的票数。
 * @param expiresAt 预留到期时间，按 UTC+8 本地时间传递且精度不超过毫秒；当前尚无自动过期释放任务。
 */
public record StockReservationRequest(String orderId, Long sessionId, Long ticketTierId,
                                      Integer quantity, LocalDateTime expiresAt) {
}
