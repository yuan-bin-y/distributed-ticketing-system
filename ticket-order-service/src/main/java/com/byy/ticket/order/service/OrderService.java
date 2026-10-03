package com.byy.ticket.order.service;

import com.byy.ticket.order.dto.order.OrderPreviewDTO;
import com.byy.ticket.order.dto.order.OrderStockReserveDTO;
import com.byy.ticket.order.vo.order.OrderPreviewVO;
import com.byy.ticket.order.vo.order.OrderStockReservationVO;

/**
 * 定义订单服务当前的业务能力：预览金额，以及内部库存预留调用。
 */
public interface OrderService {
    /**
     * 通过活动服务读取规则，校验当前售票时间与本次数量，再计算预览金额。
     */
    OrderPreviewVO preview(OrderPreviewDTO request);
    /**
     * 通过库存服务预留指定票档数量，返回预留结果；当前不会落订单表或再次读取活动规则。
     */
    OrderStockReservationVO reserveStock(OrderStockReserveDTO request);
}
