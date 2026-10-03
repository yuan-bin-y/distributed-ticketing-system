package com.byy.ticket.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.order.model.TicketOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;

/** 订单查询与状态更新；任务领取和完成都用条件 SQL，协调多个订单实例。 */
@Mapper
public interface OrderMapper extends BaseMapper<TicketOrder> {
    /** 同一用户的购买幂等键对应唯一订单。 */
    @Select("SELECT * FROM t_order WHERE user_id = #{userId} AND idempotency_key = #{key}")
    TicketOrder selectByRequest(@Param("userId") Long userId, @Param("key") String key);

    /** 只返回属于当前用户的订单，避免通过编号查询他人订单。 */
    @Select("SELECT * FROM t_order WHERE order_no = #{orderNo} AND user_id = #{userId}")
    TicketOrder selectOwned(@Param("userId") Long userId, @Param("orderNo") String orderNo);

    /** 有索引且限量扫描到期任务；已被领取且租约未到期的任务不重复领取。 */
    @Select("""
            SELECT id FROM t_order
             WHERE status IN ('STOCK_PENDING','PENDING_PAYMENT','CLOSING')
               AND next_attempt_at <= CURRENT_TIMESTAMP(3)
               AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP(3))
             ORDER BY next_attempt_at, id LIMIT #{limit}
            """)
    List<Long> selectDue(@Param("limit") int limit);

    /** 原子领取，持久化尝试次数与令牌；进程重启后可在租约到期时重新领取。 */
    @Update("""
            UPDATE t_order SET lease_token = #{token},
                lease_until = TIMESTAMPADD(MICROSECOND, #{leaseMicros}, CURRENT_TIMESTAMP(3)),
                attempt_count = attempt_count + 1
             WHERE id = #{id} AND status IN ('STOCK_PENDING','PENDING_PAYMENT','CLOSING')
               AND next_attempt_at <= CURRENT_TIMESTAMP(3)
               AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP(3))
            """)
    int claim(@Param("id") Long id, @Param("token") String token, @Param("leaseMicros") long leaseMicros);

    /** 释放库存前先持久化 CLOSING 和预留编号；宕机后恢复任务能继续释放。 */
    @Update("""
            UPDATE t_order SET status = 'CLOSING', reservation_id = #{reservationId}
             WHERE id = #{id} AND lease_token = #{token} AND status = #{expected}
            """)
    int beginClosing(@Param("id") Long id, @Param("token") String token,
                     @Param("expected") String expected, @Param("reservationId") String reservationId);

    /** 带令牌与前置状态完成当前步骤，旧领取者不能覆盖新领取者或已推进的订单。 */
    @Update("""
            UPDATE t_order SET status = #{target}, reservation_id = #{reservationId},
                next_attempt_at = #{next}, last_error = #{error}, lease_token = NULL, lease_until = NULL
             WHERE id = #{id} AND lease_token = #{token} AND status = #{expected}
            """)
    int finish(@Param("id") Long id, @Param("token") String token, @Param("expected") String expected,
               @Param("target") String target, @Param("reservationId") String reservationId,
               @Param("next") LocalDateTime next, @Param("error") String error);
}
