package com.byy.ticket.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.payment.model.OutboxEvent;
import org.apache.ibatis.annotations.*;

/** Outbox持久化；插入使用调用方付款事务，唯一约束阻止重复业务事件。 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
    /** 领取到期或租约过期的任务，最多二十条，发送时不持有数据库锁。 */
    @Select("SELECT * FROM t_outbox_event WHERE (status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3)) "
            + "OR (status='SENDING' AND lease_until<=CURRENT_TIMESTAMP(3)) ORDER BY id LIMIT 20")
    java.util.List<OutboxEvent> selectDue();

    @Update("UPDATE t_outbox_event SET status='SENDING',lease_token=#{token},"
            + "lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)),attempt_count=attempt_count+1 "
            + "WHERE id=#{id} AND ((status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3)) "
            + "OR (status='SENDING' AND lease_until<=CURRENT_TIMESTAMP(3)))")
    int claim(@Param("id") Long id,@Param("token") String token);

    /** Broker确认只代表已发布；不代表订单已经消费或出票。 */
    @Update("UPDATE t_outbox_event SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(3),"
            + "lease_token=NULL,lease_until=NULL,last_error=NULL "
            + "WHERE id=#{id} AND status='SENDING' AND lease_token=#{token} AND lease_until>CURRENT_TIMESTAMP(3)")
    int published(@Param("id") Long id,@Param("token") String token);

    @Update("UPDATE t_outbox_event SET status='PENDING',lease_token=NULL,lease_until=NULL,last_error=#{error},"
            + "next_attempt_at=TIMESTAMPADD(SECOND,LEAST(300,5*POW(2,LEAST(attempt_count-1,6))),CURRENT_TIMESTAMP(3)) "
            + "WHERE id=#{id} AND status='SENDING' AND lease_token=#{token} AND lease_until>CURRENT_TIMESTAMP(3)")
    int retry(@Param("id") Long id,@Param("token") String token,@Param("error") String error);
    /** 用支付编号查询原支付成功事件；重复付款不能重置发送状态或生成新编号。 */
    @Select("SELECT * FROM t_outbox_event WHERE event_type='PAYMENT_SUCCEEDED' AND aggregate_id=#{paymentNo}")
    OutboxEvent selectPaymentSucceeded(@Param("paymentNo") String paymentNo);
}
