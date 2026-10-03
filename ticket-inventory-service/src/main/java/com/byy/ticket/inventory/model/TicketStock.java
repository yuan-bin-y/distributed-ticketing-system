package com.byy.ticket.inventory.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 库存数据库 t_ticket_stock 的实体：每个票档一条库存计数记录。
 * 总量 = 可用量 + 预留量 + 售出量；数量变更由条件 SQL 和本地事务保证。
 */
@Data
@TableName("t_ticket_stock")
public class TicketStock {
    /**
     * 票档 ID，用于关联规则与库存。
     */
    @TableId(value = "ticket_tier_id", type = IdType.INPUT)
    private Long ticketTierId;
    /**
     * 所属场次 ID，跨服务传递时作为业务标识。
     */
    private Long sessionId;
    /**
     * 该票档初始化的总库存。
     */
    private Integer totalQuantity;
    /**
     * 尚未被占用，可以继续预留的数量。
     */
    private Integer availableQuantity;
    /**
     * 已预留但尚未确认售出的数量。
     */
    private Integer reservedQuantity;
    /**
     * 已经确认售出的数量。
     */
    private Integer soldQuantity;
    /**
     * 记录创建时间，由数据库默认值写入。
     */
    private LocalDateTime createdAt;
    /**
     * 记录最后更新时间，由数据库维护。
     */
    private LocalDateTime updatedAt;
}
