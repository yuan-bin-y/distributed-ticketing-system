package com.byy.ticket.event.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 数据库实体，对应 t_event 表。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_event")
public class Event {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String name;
    private String category;
    private String coverUrl;
    private String description;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
