package com.byy.ticket.event.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/** 数据库实体，对应 t_ticket_tier 表。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ticket_tier")
public class TicketTier {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String name;
    private BigDecimal price;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
