package com.byy.ticket.order.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 活动服务的响应契约；订单服务不依赖活动服务的业务模块。 */
public record TicketPurchaseRuleResponse(
        Long eventId, Long sessionId, Long ticketTierId, String ticketTierName,
        BigDecimal price, LocalDateTime saleStartTime, LocalDateTime saleEndTime,
        Integer purchaseLimit
) {
}
