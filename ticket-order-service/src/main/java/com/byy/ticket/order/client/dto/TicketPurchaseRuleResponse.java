package com.byy.ticket.order.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单侧定义的活动规则 JSON 接收对象，对应活动服务返回的规则字段。
 * 这是 HTTP 数据契约，不是直接引用活动服务的实体或 VO。
 * 
 * @param eventId 所属活动 ID。
 * @param sessionId 所属场次 ID，跨服务传递时作为业务标识。
 * @param ticketTierId 票档 ID，用于关联规则与库存。
 * @param ticketTierName 票档名称。
 * @param price 票档单价，使用 BigDecimal 保存金额。
 * @param saleStartTime 允许购买的开始时间。
 * @param saleEndTime 停止购买的时间，订单预览按不包含此时刻的边界判断。
 * @param purchaseLimit 单次购票数量上限；当前未按用户统计历史购买次数。
 */
public record TicketPurchaseRuleResponse(
        Long eventId, Long sessionId, Long ticketTierId, String ticketTierName,
        BigDecimal price, LocalDateTime saleStartTime, LocalDateTime saleEndTime,
        Integer purchaseLimit
) {
}
