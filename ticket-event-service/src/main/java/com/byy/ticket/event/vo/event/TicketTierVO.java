package com.byy.ticket.event.vo.event;

import java.math.BigDecimal;

/** 活动查询展示数据。 */
public record TicketTierVO(Long id, String name, BigDecimal price) {
}
