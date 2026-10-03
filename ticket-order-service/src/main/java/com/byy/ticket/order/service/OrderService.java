package com.byy.ticket.order.service;

import com.byy.ticket.order.dto.order.OrderPreviewDTO;
import com.byy.ticket.order.dto.order.OrderStockReserveDTO;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.vo.order.OrderDetailVO;
import com.byy.ticket.order.vo.order.OrderPreviewVO;
import com.byy.ticket.order.vo.order.OrderStockReservationVO;

/**
 * 定义订单创建、归属查询、预览与内部库存协作能力。
 */
public interface OrderService {
    /** 幂等创建订单，落库后调用库存；结果不确定时返回持久化的处理中状态。 */
    OrderDetailVO create(Long userId, OrderCreateDTO request);

    /** 只允许订单所属用户读取订单及购买快照。 */
    OrderDetailVO getOrder(Long userId, String orderNo);
    /**
     * 通过活动服务读取规则，校验当前售票时间与本次数量，再计算预览金额。
     */
    OrderPreviewVO preview(OrderPreviewDTO request);
    /**
     * 通过库存服务预留指定票档数量，返回预留结果；当前不会落订单表或再次读取活动规则。
     */
    OrderStockReservationVO reserveStock(OrderStockReserveDTO request);
}
