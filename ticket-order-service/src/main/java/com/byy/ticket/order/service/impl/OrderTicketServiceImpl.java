package com.byy.ticket.order.service.impl;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.order.mapper.*;
import com.byy.ticket.order.service.OrderTicketService;
import com.byy.ticket.order.vo.order.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 查询订单归属及电子票；可重复读保证看到同一版本的订单状态和出票列表。 */
@Service
public class OrderTicketServiceImpl implements OrderTicketService {
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final ElectronicTicketMapper tickets;

    /** 注入本服务Mapper，遵循Controller→Service→Impl→Mapper分层。 */
    public OrderTicketServiceImpl(OrderMapper orders,OrderItemMapper items,ElectronicTicketMapper tickets) {
        this.orders=orders;this.items=items;this.tickets=tickets;
    }

    /** 先检查用户归属，再在同一数据库快照中读取购买信息和电子票，避免泄露他人票号。 */
    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OrderTicketsVO getTickets(Long userId,String orderNo) {
        if(userId==null || userId<1 || orderNo==null || !orderNo.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("用户或订单编号不正确");
        }
        var order=orders.selectOwned(userId,orderNo);
        if(order==null) { throw new ResourceNotFoundException("订单不存在"); }
        var item=items.selectByOrderId(order.getId());
        if(item==null) { throw new IllegalStateException("订单购买快照缺失"); }
        var result=tickets.selectByItem(item.getId()).stream().map(ticket->new ElectronicTicketVO(
                ticket.getTicketNo(),ticket.getTicketIndex(),ticket.getStatus(),item.getEventId(),
                item.getSessionId(),item.getTicketTierId(),item.getTicketTierName(),ticket.getCreatedAt())).toList();
        return new OrderTicketsVO(orderNo,order.getStatus(),result);
    }
}
