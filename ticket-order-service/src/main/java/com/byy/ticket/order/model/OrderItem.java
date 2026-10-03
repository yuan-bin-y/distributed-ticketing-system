package com.byy.ticket.order.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单项实体，本阶段每张订单只购买一个票档。 */
@Data
@TableName("t_order_item")
public class OrderItem {
    /** 订单项主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 本服务订单主表 ID。 */
    private Long orderId;
    /** 活动 ID，不建立跨库外键。 */
    private Long eventId;
    /** 场次 ID。 */
    private Long sessionId;
    /** 票档 ID。 */
    private Long ticketTierId;
    /** 名称快照。 */
    private String ticketTierName;
    /** 单价快照。 */
    private BigDecimal unitPrice;
    /** 购买数量。 */
    private Integer quantity;
    /** 单价乘数量。 */
    private BigDecimal subtotalAmount;
}
