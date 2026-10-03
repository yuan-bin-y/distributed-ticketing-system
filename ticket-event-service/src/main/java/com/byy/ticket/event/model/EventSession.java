package com.byy.ticket.event.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 活动数据库 t_event_session 的实体：描述活动的场次、地点、时间和购票规则。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_event_session")
public class EventSession {
    /**
     * 当前记录的数据库主键。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 所属活动 ID。
     */
    private Long eventId;
    /**
     * 当前活动、场次或票档的名称。
     */
    private String name;
    /**
     * 场次举办场馆名称。
     */
    private String venueName;
    /**
     * 场馆地址。
     */
    private String venueAddress;
    /**
     * 场次开始时间。
     */
    private LocalDateTime startTime;
    /**
     * 场次结束时间。
     */
    private LocalDateTime endTime;
    /**
     * 允许购买的开始时间。
     */
    private LocalDateTime saleStartTime;
    /**
     * 停止购买的时间，订单预览按不包含此时刻的边界判断。
     */
    private LocalDateTime saleEndTime;
    /**
     * 单次购票数量上限；当前未按用户统计历史购买次数。
     */
    private Integer purchaseLimit;
    /**
     * 发布状态，活动查询只对外展示 PUBLISHED 的记录。
     */
    private String status;
    /**
     * 记录创建时间，由数据库默认值写入。
     */
    private LocalDateTime createdAt;
    /**
     * 记录最后更新时间，由数据库维护。
     */
    private LocalDateTime updatedAt;
}
