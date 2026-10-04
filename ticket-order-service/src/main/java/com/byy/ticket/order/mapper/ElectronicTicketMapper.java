package com.byy.ticket.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.order.model.ElectronicTicket;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 电子票只访问订单数据库；按订单项与序号唯一，批量插入仍参与出票本地事务。 */
@Mapper
public interface ElectronicTicketMapper extends BaseMapper<ElectronicTicket> {
    /** 查询按第几张票排序的列表，供当前用户读取。 */
    @Select("SELECT * FROM t_ticket WHERE order_item_id=#{itemId} ORDER BY ticket_index")
    List<ElectronicTicket> selectByItem(@Param("itemId") Long itemId);

    /** 已锁定订单后锁定现有票，保留原编号并检查是否存在异常序号。 */
    @Select("SELECT * FROM t_ticket WHERE order_item_id=#{itemId} ORDER BY ticket_index FOR UPDATE")
    List<ElectronicTicket> selectByItemForUpdate(@Param("itemId") Long itemId);

    /** 分批写入缺少的票；任何唯一键或数据库错误向上抛出，整次出票回滚。 */
    @Insert("""
            <script>
            INSERT INTO t_ticket(ticket_no,order_item_id,ticket_index,status) VALUES
            <foreach collection="tickets" item="ticket" separator=",">
            (#{ticket.ticketNo},#{ticket.orderItemId},#{ticket.ticketIndex},'VALID')
            </foreach>
            </script>
            """)
    int insertTickets(@Param("tickets") List<ElectronicTicket> tickets);
}
