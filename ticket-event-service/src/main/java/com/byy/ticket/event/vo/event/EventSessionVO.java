package com.byy.ticket.event.vo.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场次展示数据：包含举办信息、售票规则和可展示的票档列表。
 * 
 * @param id 当前记录的数据库主键。
 * @param name 当前活动、场次或票档的名称。
 * @param venueName 场次举办场馆名称。
 * @param venueAddress 场馆地址。
 * @param startTime 场次开始时间。
 * @param endTime 场次结束时间。
 * @param saleStartTime 允许购买的开始时间。
 * @param saleEndTime 停止购买的时间，订单预览按不包含此时刻的边界判断。
 * @param purchaseLimit 单次购票数量上限；当前未按用户统计历史购买次数。
 * @param ticketTiers 该场次可展示的启用票档列表。
 */
public record EventSessionVO(Long id, String name, String venueName, String venueAddress,
                             LocalDateTime startTime, LocalDateTime endTime,
                             LocalDateTime saleStartTime, LocalDateTime saleEndTime,
                             Integer purchaseLimit, List<TicketTierVO> ticketTiers) {
    /**
     * 票档为空时转为空集合，并复制列表，避免返回对象被外部修改。
     */
    public EventSessionVO {
        ticketTiers = ticketTiers == null ? List.of() : List.copyOf(ticketTiers);
    }
}
