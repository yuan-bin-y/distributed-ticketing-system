package com.byy.ticket.order.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.dto.order.OrderStockReserveDTO;
import com.byy.ticket.order.service.OrderService;
import com.byy.ticket.order.vo.order.OrderStockReservationVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用于验证订单服务调用库存服务的内部 HTTP 入口。
 * 当前只是预留调用入口，还没有创建实际订单、用户身份校验或支付流程。
 */
@RestController
@RequestMapping("/internal/orders/stock-reservations")
public class InternalOrderStockController {
    private final OrderService orderService;

    /**
     * 注入订单业务接口，HTTP 参数由 Controller 交给本地业务实现。
     */
    public InternalOrderStockController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * POST /internal/orders/stock-reservations：校验请求字段，调用 OrderService.reserveStock。
     * 订单业务层通过 InventoryClient 发送 HTTP 请求给库存服务，再把结果包装返回。
     */
    @PostMapping
    public Result<OrderStockReservationVO> reserve(@Valid @RequestBody OrderStockReserveDTO request) {
        return Result.success(orderService.reserveStock(request));
    }
}
