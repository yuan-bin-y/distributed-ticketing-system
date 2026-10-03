package com.byy.ticket.event.vo.event;

import java.math.BigDecimal;

/**
 * 展示场次中的票档名称和单价；此响应不表示实时可用库存。
 * 
 * @param id 当前记录的数据库主键。
 * @param name 当前活动、场次或票档的名称。
 * @param price 票档单价，使用 BigDecimal 保存金额。
 */
public record TicketTierVO(Long id, String name, BigDecimal price) {
}
