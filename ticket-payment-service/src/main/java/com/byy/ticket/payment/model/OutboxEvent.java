package com.byy.ticket.payment.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 待发布事件与投递进度；属于Payment库，不能用发送状态推断订单是否履约。 */
@Data
@TableName("t_outbox_event")
public class OutboxEvent {
    /** 本库自增主键，用于后台有界扫描。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 稳定事件编号，后续重复发布不得换号。 */
    private String eventId;
    /** 事件类型，目前为PAYMENT_SUCCEEDED。 */
    private String eventType;
    /** 对应稳定支付编号；与事件类型一起唯一。 */
    private String aggregateId;
    /** 原始JSON消息；持久化后不随重试重新组装。 */
    private String payload;
    /** 原付款请求追踪编号，后台线程可据此恢复日志关联。 */
    private String traceId;
    /** PENDING、SENDING、PUBLISHED或REVIEW_REQUIRED；当前阶段仅创建PENDING。 */
    private String status;
    /** 发布尝试次数，当前初始为零。 */
    private Integer attemptCount;
    /** 后台发送器允许尝试的时间；发送器后续接入。 */
    private LocalDateTime nextAttemptAt;
    /** 后续多实例领取令牌，过期旧任务不能覆盖新领取者。 */
    private String leaseToken;
    /** 领取期限，发送进程宕机后用于恢复。 */
    private LocalDateTime leaseUntil;
    /** 最近一次失败的有界说明。 */
    private String lastError;
    /** 事件创建时间，按固定东八区解释。 */
    private LocalDateTime createdAt;
    /** 确认MQ发布完成的时间；HTTP通知成功不能填写此字段。 */
    private LocalDateTime publishedAt;
}
