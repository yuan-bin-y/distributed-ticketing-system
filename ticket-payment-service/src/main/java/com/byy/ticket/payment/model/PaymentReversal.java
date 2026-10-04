package com.byy.ticket.payment.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 全额模拟冲正记录，与支付单在同一库；不抹掉原付款成功事实。 */
@Data
@TableName("t_payment_reversal")
public class PaymentReversal {
    /** 本库主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 对外稳定冲正编号。 */
    private String reversalNo;
    /** 本库支付单 ID，唯一约束阻止重复冲正。 */
    private Long paymentId;
    /** 全额冲正金额，只能从支付单快照读取。 */
    private BigDecimal amount;
    /** 首次冲正原因，重复请求不得改写。 */
    private String reason;
    /** PENDING 或 SUCCESS；模拟操作在同一事务中完成。 */
    private String status;
    /** 模拟冲正完成时间。 */
    private LocalDateTime completedAt;
    /** 数据库维护的创建时间。 */
    private LocalDateTime createdAt;
    /** 数据库维护的更新时间。 */
    private LocalDateTime updatedAt;
}
