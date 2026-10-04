package com.byy.ticket.order.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.dto.order.OrderPreviewDTO;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.vo.order.OrderDetailVO;
import com.byy.ticket.order.vo.order.OrderPaymentVO;
import com.byy.ticket.order.web.OrderIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.byy.ticket.order.service.OrderService;
import com.byy.ticket.order.vo.order.OrderPreviewVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单创建、归属查询、发起支付和预览入口；创建202表示库存待核对，200表示已有明确状态。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;
    private final OrderIdentityResolver identity;

    /**
     * 注入订单业务接口，由 Spring 提供 OrderServiceImpl 实例。
     */
    public OrderController(OrderService orderService, OrderIdentityResolver identity) {
        this.orderService = orderService;
        this.identity = identity;
    }

    /** POST /api/orders：读取可信身份，创建或返回同一购买请求对应的订单。 */
    @PostMapping
    public ResponseEntity<Result<OrderDetailVO>> create(@Valid @RequestBody OrderCreateDTO request,
                                                        HttpServletRequest servletRequest) {
        OrderDetailVO order = orderService.create(identity.requireUser(servletRequest), request);
        boolean pending = "STOCK_PENDING".equals(order.status()) || "CLOSING".equals(order.status());
        return ResponseEntity.status(pending ? 202 : 200).body(Result.success(order));
    }

    /** GET /api/orders/{orderNo}：查询属于当前用户的订单，不能通过编号读取他人订单。 */
    @GetMapping("/{orderNo}")
    public Result<OrderDetailVO> getOrder(@PathVariable String orderNo, HttpServletRequest request) {
        return Result.success(orderService.getOrder(identity.requireUser(request), orderNo));
    }

    /** POST /api/orders/{orderNo}/payments：只提交订单编号，从订单快照创建支付单。 */
    @PostMapping("/{orderNo}/payments")
    public Result<OrderPaymentVO> createPayment(@PathVariable String orderNo, HttpServletRequest request) {
        return Result.success(orderService.createPayment(identity.requireUser(request), orderNo));
    }

    /**
     * POST /api/orders/preview：接收票档 ID 和数量，调用业务层查询规则、校验开售时间并计算总价。
     */
    @PostMapping("/preview")
    public Result<OrderPreviewVO> preview(@Valid @RequestBody OrderPreviewDTO request) {
        return Result.success(orderService.preview(request));
    }
}
