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

/**
 * 活动数据库 t_ticket_tier 的实体：描述某个场次的票档名称和价格。
 * 库存数量由库存服务管理，不放在此实体中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ticket_tier")
public class TicketTier {
    /**
     * 当前记录的数据库主键。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 所属场次 ID，跨服务传递时作为业务标识。
     */
    private Long sessionId;
    /**
     * 当前活动、场次或票档的名称。
     */
    private String name;
    /**
     * 票档单价，使用 BigDecimal 保存金额。
     */
    private BigDecimal price;
    /**
     * 票档是否启用，1 表示启用。
     */
    private Integer enabled;
    /** 初始化请求快照，仅用于跨服务核对，实时库存仍只属于Inventory。 */
    private Integer plannedQuantity;
    /** LEGACY历史未核对、PENDING待准备、READY已确认、REVIEW_REQUIRED需核对。 */
    private String preparationStatus;
    private Integer preparationAttempts;
    private LocalDateTime preparationNextAt;
    private String preparationToken;
    private LocalDateTime preparationLeaseUntil;
    private String preparationError;
    /**
     * 记录创建时间，由数据库默认值写入。
     */
    private LocalDateTime createdAt;
    /**
     * 记录最后更新时间，由数据库维护。
     */
    private LocalDateTime updatedAt;
}
