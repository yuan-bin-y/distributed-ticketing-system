package com.byy.ticket.payment.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付单实体；支付成功事实和待通知进度属于同一条数据库记录。 */
@Data
@TableName("t_payment")
public class Payment {
    /** 本库主键，由数据库生成。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 对外稳定支付编号；重试不能换新编号。 */
    private String paymentNo;
    /** 订单服务的订单编号；唯一约束保证一张订单只有一张支付单。 */
    private String orderNo;
    /** 所属用户，来自订单服务。 */
    private Long userId;
    /** 应付金额快照，人民币两位小数。 */
    private BigDecimal amount;
    /** CREATED 或 SUCCESS；冲正后仍保留曾经成功的付款事实。 */
    private String status;
    /** 从订单传来的固定到期时间，不在重试中延长。 */
    private LocalDateTime expiresAt;
    /** 首次付款成功时间，不被重复请求改写。 */
    private LocalDateTime paidAt;
    /** NONE、PENDING、DELIVERED；本阶段只产生 PENDING，不实际发送。 */
    private String notifyStatus;
    /** 后续通知任务的处理时间，付款成功时与支付事实原子保存。 */
    private LocalDateTime nextNotifyAt;
    /** 后续通知尝试次数，当前阶段保持零。 */
    private Integer notifyAttemptCount;
    /** 后续通知失败原因，当前阶段为空。 */
    private String lastNotifyError;
    /** 数据库维护的创建时间。 */
    private LocalDateTime createdAt;
    /** 数据库维护的更新时间。 */
    private LocalDateTime updatedAt;
}
