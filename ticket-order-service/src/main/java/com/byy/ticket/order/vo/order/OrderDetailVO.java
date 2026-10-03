package com.byy.ticket.order.vo.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单查询及创建结果；STOCK_PENDING 或 CLOSING 表示后台仍在核对。
 * @param orderNo 稳定订单编号。
 * @param status 当前订单状态。
 * @param eventId 活动 ID。
 * @param sessionId 场次 ID。
 * @param ticketTierId 票档 ID。
 * @param ticketTierName 下单时的名称快照。
 * @param unitPrice 下单时的单价快照。
 * @param quantity 购买数量。
 * @param totalAmount 订单总金额。
 * @param expiresAt 支付截止时间。
 * @param reservationId 已确认的库存预留编号，可为空。
 * @param createdAt 订单创建时间。
 */
public record OrderDetailVO(String orderNo, String status, Long eventId, Long sessionId,
                            Long ticketTierId, String ticketTierName, BigDecimal unitPrice,
                            Integer quantity, BigDecimal totalAmount, LocalDateTime expiresAt,
                            String reservationId, LocalDateTime createdAt) {
}
