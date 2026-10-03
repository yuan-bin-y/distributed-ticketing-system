package com.byy.ticket.order.vo.order;

import java.math.BigDecimal;

/**
 * 订单预览响应：返回票档信息、购买数量和计算金额，不代表订单已经创建。
 * 
 * @param eventId 所属活动 ID。
 * @param sessionId 所属场次 ID，跨服务传递时作为业务标识。
 * @param ticketTierId 票档 ID，用于关联规则与库存。
 * @param ticketTierName 票档名称。
 * @param unitPrice 单张票的价格。
 * @param quantity 本次购买或预留的票数。
 * @param totalAmount 单价乘数量计算得到的预览总金额。
 */
public record OrderPreviewVO(
        Long eventId, Long sessionId, Long ticketTierId, String ticketTierName,
        BigDecimal unitPrice, Integer quantity, BigDecimal totalAmount
) {
}
