package com.byy.ticket.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.inventory.model.TicketStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 库存计数 SQL：通过数据库条件更新一次完成数量检查和修改，避免并发请求先查后改造成超卖。
 */
@Mapper
public interface TicketStockMapper extends BaseMapper<TicketStock> {
    /**
     * 可用量足够且场次匹配时，可用量减 quantity、预留量加 quantity。
     * 返回影响行数：1 表示成功，0 表示条件不满足；Service 再区分库存不存在、场次不匹配或数量不足。
     */
    @Update("""
            UPDATE t_ticket_stock
               SET available_quantity = available_quantity - #{quantity},
                   reserved_quantity = reserved_quantity + #{quantity}
             WHERE ticket_tier_id = #{ticketTierId} AND session_id = #{sessionId}
               AND available_quantity >= #{quantity}
            """)
    int reserve(@Param("ticketTierId") Long ticketTierId, @Param("sessionId") Long sessionId,
                @Param("quantity") int quantity);

    /**
     * 预留量足够时，将 quantity 从预留量转入售出量；影响行数不为 1 时由 Service 回滚事务。
     */
    @Update("""
            UPDATE t_ticket_stock
               SET reserved_quantity = reserved_quantity - #{quantity},
                   sold_quantity = sold_quantity + #{quantity}
             WHERE ticket_tier_id = #{ticketTierId} AND session_id = #{sessionId}
               AND reserved_quantity >= #{quantity}
            """)
    int confirm(@Param("ticketTierId") Long ticketTierId, @Param("sessionId") Long sessionId,
                @Param("quantity") int quantity);

    /**
     * 预留量足够时，将 quantity 从预留量归还可用量；影响行数不为 1 时由 Service 回滚事务。
     */
    @Update("""
            UPDATE t_ticket_stock
               SET reserved_quantity = reserved_quantity - #{quantity},
                   available_quantity = available_quantity + #{quantity}
             WHERE ticket_tier_id = #{ticketTierId} AND session_id = #{sessionId}
               AND reserved_quantity >= #{quantity}
            """)
    int release(@Param("ticketTierId") Long ticketTierId, @Param("sessionId") Long sessionId,
                @Param("quantity") int quantity);

    /**
     * 按票档 ID 加行锁读取当前库存，用于条件扣减失败后判断具体原因。
     * FOR UPDATE 必须在事务中使用，锁随事务提交或回滚释放。
     */
    @Select("SELECT * FROM t_ticket_stock WHERE ticket_tier_id = #{ticketTierId} FOR UPDATE")
    TicketStock selectForUpdate(@Param("ticketTierId") Long ticketTierId);
}
