package com.byy.ticket.order.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.dto.order.OrderPreviewDTO;
import com.byy.ticket.order.service.OrderService;
import com.byy.ticket.order.vo.order.OrderPreviewVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单对用户开放的 HTTP 入口；当前提供预览，不创建订单或扣减库存。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    /**
     * 注入订单业务接口，由 Spring 提供 OrderServiceImpl 实例。
     */
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * POST /api/orders/preview：接收票档 ID 和数量，调用业务层查询规则、校验开售时间并计算总价。
     */
    @PostMapping("/preview")
    public Result<OrderPreviewVO> preview(@Valid @RequestBody OrderPreviewDTO request) {
        return Result.success(orderService.preview(request));
    }
}
