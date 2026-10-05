package com.byy.ticket.event.vo.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 活动服务通过内部 HTTP 接口返回的购票规则；Spring 将此对象序列化为 JSON。
 * 
 * @param eventId 所属活动 ID。
 * @param sessionId 所属场次 ID，跨服务传递时作为业务标识。
 * @param ticketTierId 票档 ID，用于关联规则与库存。
 * @param ticketTierName 票档名称。
 * @param price 票档单价，使用 BigDecimal 保存金额。
 * @param saleStartTime 允许购买的开始时间。
 * @param saleEndTime 停止购买的时间，订单预览按不包含此时刻的边界判断。
 * @param purchaseLimit 每位用户在该场次的累计购票上限，由Order在正式下单时执行。
 */
public record TicketPurchaseRuleVO(
        Long eventId,
        Long sessionId,
        Long ticketTierId,
        String ticketTierName,
        BigDecimal price,
        LocalDateTime saleStartTime,
        LocalDateTime saleEndTime,
        Integer purchaseLimit
) {
}
