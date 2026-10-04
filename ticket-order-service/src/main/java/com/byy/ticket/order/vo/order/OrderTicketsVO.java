package com.byy.ticket.order.vo.order;

import java.util.List;

/** 返回订单状态和电子票列表；未完成出票时列表为空，状态用于区分未付款与待出票。 */
public record OrderTicketsVO(String orderNo,String orderStatus,List<ElectronicTicketVO> tickets) { }
