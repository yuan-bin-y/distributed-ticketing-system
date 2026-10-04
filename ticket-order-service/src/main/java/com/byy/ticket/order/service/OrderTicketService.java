package com.byy.ticket.order.service;

import com.byy.ticket.order.vo.order.OrderTicketsVO;

/** 当前用户的电子票查询能力；不提供手动出票或修改票状态的公共接口。 */
public interface OrderTicketService {
    /** 查询属于当前用户的原订单和电子票，其他用户与不存在订单统一返回不存在。 */
    OrderTicketsVO getTickets(Long userId,String orderNo);
}
