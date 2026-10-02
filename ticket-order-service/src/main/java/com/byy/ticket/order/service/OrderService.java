package com.byy.ticket.order.service;

import com.byy.ticket.order.dto.order.OrderPreviewDTO;
import com.byy.ticket.order.vo.order.OrderPreviewVO;

/** 订单业务接口。 */
public interface OrderService {
    OrderPreviewVO preview(OrderPreviewDTO request);
}
