package com.byy.ticket.event.vo.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 提供给订单服务的购票规则；时间统一按固定东八区解释。 */
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
