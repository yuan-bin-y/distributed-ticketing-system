package com.byy.ticket.event.vo.event;

import java.time.LocalDateTime;
import java.util.List;

/** 活动查询展示数据。 */
public record EventSessionVO(Long id, String name, String venueName, String venueAddress,
                             LocalDateTime startTime, LocalDateTime endTime,
                             LocalDateTime saleStartTime, LocalDateTime saleEndTime,
                             Integer purchaseLimit, List<TicketTierVO> ticketTiers) {
    public EventSessionVO {
        ticketTiers = ticketTiers == null ? List.of() : List.copyOf(ticketTiers);
    }
}
