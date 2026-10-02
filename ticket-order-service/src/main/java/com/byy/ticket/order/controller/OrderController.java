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

/** 购票预览接口。 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/preview")
    public Result<OrderPreviewVO> preview(@Valid @RequestBody OrderPreviewDTO request) {
        return Result.success(orderService.preview(request));
    }
}
