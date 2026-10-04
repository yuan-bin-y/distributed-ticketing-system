package com.byy.ticket.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.payment.model.Payment;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

/** 支付单 SQL；唯一订单编号控制创建幂等，行锁协调同一支付单状态操作。 */
@Mapper
public interface PaymentMapper extends BaseMapper<Payment> {
    /** 有界扫描待发送记录，不重复扫描尚在租约中的任务。 */
    @Select("""
            SELECT id FROM t_payment WHERE notify_status='PENDING' AND status='SUCCESS'
                AND next_notify_at <= CURRENT_TIMESTAMP(3)
                AND (notify_lease_until IS NULL OR notify_lease_until <= CURRENT_TIMESTAMP(3))
            ORDER BY next_notify_at,id LIMIT #{limit}
            """)
    java.util.List<Long> selectNotifyDue(@Param("limit") int limit);

    /** 原子竞争通知任务；次数与令牌随领取保存，重启后可继续。 */
    @Update("""
            UPDATE t_payment SET notify_lease_token=#{token},
                notify_lease_until=TIMESTAMPADD(MICROSECOND,#{leaseMicros},CURRENT_TIMESTAMP(3)),
                notify_attempt_count=notify_attempt_count+1
            WHERE id=#{id} AND status='SUCCESS' AND notify_status='PENDING'
                AND next_notify_at <= CURRENT_TIMESTAMP(3)
                AND (notify_lease_until IS NULL OR notify_lease_until <= CURRENT_TIMESTAMP(3))
            """)
    int claimNotification(@Param("id") Long id, @Param("token") String token,
                          @Param("leaseMicros") long leaseMicros);

    /** 只有可靠接收确认才完成；令牌不匹配的旧发送者不能覆盖新发送者。 */
    @Update("""
            UPDATE t_payment SET notify_status=#{status},next_notify_at=#{next},last_notify_error=#{error},
                notify_lease_token=NULL,notify_lease_until=NULL
            WHERE id=#{id} AND notify_status='PENDING' AND notify_lease_token=#{token}
            """)
    int finishNotification(@Param("id") Long id, @Param("token") String token,
                           @Param("status") String status, @Param("next") LocalDateTime next,
                           @Param("error") String error);
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
