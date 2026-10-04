package com.byy.ticket.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.payment.model.PaymentReversal;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

/** 冲正记录 SQL；调用方先锁定支付单，同一支付单只有一条全额冲正。 */
@Mapper
public interface PaymentReversalMapper extends BaseMapper<PaymentReversal> {
    /** 查询支付单已有的冲正，用于幂等返回与结果核对。 */
    @Select("SELECT * FROM t_payment_reversal WHERE payment_id = #{paymentId}")
    PaymentReversal selectByPayment(@Param("paymentId") Long paymentId);

    /** 在创建冲正的同一本地事务中标记模拟完成；异常时整条记录回滚。 */
    @Update("""
            UPDATE t_payment_reversal SET status='SUCCESS',completed_at=#{completedAt}
            WHERE id=#{id} AND status='PENDING'
            """)
    int markSuccess(@Param("id") Long id, @Param("completedAt") LocalDateTime completedAt);
}
