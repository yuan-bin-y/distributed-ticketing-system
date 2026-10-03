package com.byy.ticket.inventory.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 库存数据库 t_stock_reservation 的实体：记录某个订单占用了哪个票档、多少张票及当前状态。
 * orderId 唯一，用于识别重复预留；订单编号来自调用方，不跨库关联订单表。
 */
@Data
@TableName("t_stock_reservation")
public class StockReservation {
    /**
     * 库存服务生成的 32 位预留编号。
     */
    @TableId(value = "reservation_id", type = IdType.INPUT)
    private String reservationId;
    /**
     * 调用方提供的订单编号，库存预留用它识别重复请求。
     */
    private String orderId;
    /**
     * 所属场次 ID，跨服务传递时作为业务标识。
     */
    private Long sessionId;
    /**
     * 票档 ID，用于关联规则与库存。
     */
    private Long ticketTierId;
    /**
     * 本次购买或预留的票数。
     */
    private Integer quantity;
    /**
     * 预留到期时间，按 UTC+8 本地时间传递且精度不超过毫秒；当前尚无自动过期释放任务。
     */
    private LocalDateTime expiresAt;
    /**
     * 预留状态：RESERVED、SOLD 或 RELEASED。
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
