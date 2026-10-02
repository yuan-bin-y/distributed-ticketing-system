package com.byy.ticket.event.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 数据库实体，对应 t_event_session 表。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_event_session")
public class EventSession {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long eventId;
    private String name;
    private String venueName;
    private String venueAddress;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
    private Integer purchaseLimit;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
