package com.byy.ticket.order.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单主表实体，同时持久化恢复进度和租约。 */
@Data
@TableName("t_order")
public class TicketOrder {
    /** 订单主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 跨服务预留使用的稳定编号。 */
    private String orderNo;
    /** 订单归属用户。 */
    private Long userId;
    /** 用户本次购买的幂等键。 */
    private String idempotencyKey;
    /** 下单金额快照。 */
    private BigDecimal totalAmount;
    /** 订单状态机的当前状态。 */
    private String status;
    /** 库存返回的预留编号。 */
    private String reservationId;
    /** 固定的支付截止时间，重试不得重算。 */
    private LocalDateTime expiresAt;
    /** 下一次恢复或到期检查时间。 */
    private LocalDateTime nextAttemptAt;
    /** 库存处理领取次数，用于退避及保守判断。 */
    private Integer attemptCount;
    /** 领取者令牌，防止旧任务覆盖新任务。 */
    private String leaseToken;
    /** 任务租约到期时间，进程宕机后可重新领取。 */
    private LocalDateTime leaseUntil;
    /** 最近错误摘要，不存密码或异常堆栈。 */
    private String lastError;
    /** 数据库创建时间。 */
    private LocalDateTime createdAt;
    /** 数据库更新时间。 */
    private LocalDateTime updatedAt;
}
