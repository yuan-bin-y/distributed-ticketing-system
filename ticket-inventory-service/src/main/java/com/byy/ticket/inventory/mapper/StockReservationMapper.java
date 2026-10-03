package com.byy.ticket.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.inventory.model.StockReservation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 库存预留记录 SQL：利用唯一订单编号与行锁协调幂等请求和并发状态转换。
 */
@Mapper
public interface StockReservationMapper extends BaseMapper<StockReservation> {
    /**
     * 插入预留记录；唯一键冲突时保留原记录，不覆盖首次请求的参数。
     * 不能只凭影响行数判断是否为新预留，Service 会比较实际记录与候选记录的预留编号。
     */
    @Insert("""
            INSERT INTO t_stock_reservation
                (reservation_id, order_id, session_id, ticket_tier_id, quantity, expires_at, status)
            VALUES (#{reservationId}, #{orderId}, #{sessionId}, #{ticketTierId}, #{quantity}, #{expiresAt}, #{status})
            ON DUPLICATE KEY UPDATE order_id = t_stock_reservation.order_id
            """)
    int insertOrKeep(StockReservation reservation);

    /**
     * 按订单编号加行锁读取预留，用于串行处理同一订单的重复预留请求。
     */
    @Select("SELECT * FROM t_stock_reservation WHERE order_id = #{orderId} FOR UPDATE")
    StockReservation selectByOrderIdForUpdate(@Param("orderId") String orderId);

    /**
     * 按预留编号加行锁读取记录，让同一预留的确认和释放操作按顺序检查状态。
     */
    @Select("SELECT * FROM t_stock_reservation WHERE reservation_id = #{reservationId} FOR UPDATE")
    StockReservation selectForUpdate(@Param("reservationId") String reservationId);

    /**
     * 仅在记录仍为 RESERVED 时更新目标状态，避免覆盖 SOLD 或 RELEASED 等终态。
     * 与库存计数更新处于同一个事务，任一步失败都回滚。
     */
    @Update("""
            UPDATE t_stock_reservation SET status = #{status}
             WHERE reservation_id = #{reservationId} AND status = 'RESERVED'
            """)
    int changeReservedStatus(@Param("reservationId") String reservationId, @Param("status") String status);
}
