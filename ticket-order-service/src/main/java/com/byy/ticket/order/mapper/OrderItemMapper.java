package com.byy.ticket.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.order.model.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

/** 订单库购买快照的 Mapper，不读取活动或库存库。 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
    /** 本阶段一单一个票档，按订单主键读取对应快照。 */
    @Select("SELECT * FROM t_order_item WHERE order_id = #{orderId}")
    OrderItem selectByOrderId(@Param("orderId") Long orderId);
}
