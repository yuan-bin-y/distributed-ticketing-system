package com.byy.ticket.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.payment.model.Payment;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

/** 支付单 SQL；唯一订单编号控制创建幂等，行锁协调同一支付单状态操作。 */
@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {
    /** 唯一键冲突时保留原记录，由业务层比较编号和请求参数。 */
    @Insert("""
            INSERT INTO t_payment(payment_no,order_no,user_id,amount,status,expires_at,notify_status)
            VALUES(#{paymentNo},#{orderNo},#{userId},#{amount},#{status},#{expiresAt},'NONE')
            ON DUPLICATE KEY UPDATE order_no = t_payment.order_no
            """)
    int insertOrKeep(Payment payment);

    /** 按订单号锁定实际记录，让相同订单的创建请求串行核对原参数。 */
    @Select("SELECT * FROM t_payment WHERE order_no = #{orderNo} FOR UPDATE")
    Payment selectByOrderForUpdate(@Param("orderNo") String orderNo);

    /** 按支付编号锁定，模拟付款和冲正统一先取得这把锁。 */
    @Select("SELECT * FROM t_payment WHERE payment_no = #{paymentNo} FOR UPDATE")
    Payment selectForUpdate(@Param("paymentNo") String paymentNo);

    /** 按订单编号只读查询，用于远程调用结果核对。 */
    @Select("SELECT * FROM t_payment WHERE order_no = #{orderNo}")
    Payment selectByOrder(@Param("orderNo") String orderNo);

    /** 只查询属于当前用户的支付单，避免公共接口暴露其他用户数据。 */
    @Select("SELECT * FROM t_payment WHERE payment_no = #{paymentNo} AND user_id = #{userId}")
    Payment selectOwned(@Param("paymentNo") String paymentNo, @Param("userId") Long userId);

    /** 支付事实、首次成功时间和待通知状态一次更新，参与 Service 的本地事务。 */
    @Update("""
            UPDATE t_payment SET status='SUCCESS',paid_at=#{paidAt},
                notify_status='PENDING',next_notify_at=#{paidAt}
            WHERE id=#{id} AND status='CREATED' AND expires_at > #{paidAt}
            """)
    int markSuccess(@Param("id") Long id, @Param("paidAt") LocalDateTime paidAt);
}
