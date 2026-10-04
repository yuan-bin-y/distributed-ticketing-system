package com.byy.ticket.payment.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.payment.dto.payment.PaymentCreateDTO;
import com.byy.ticket.payment.service.PaymentService;
import com.byy.ticket.payment.vo.payment.PaymentVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 订单调用方的支付内部入口；网关不路由 /internal，服务间认证尚待接入。 */
@RestController
@RequestMapping("/internal/payments")
public class InternalPaymentController {
    private final PaymentService paymentService;

    /** 注入本服务业务接口，由Spring提供实现。 */
    public InternalPaymentController(PaymentService paymentService) { this.paymentService = paymentService; }

    /** 创建支付单；同一订单及相同参数重试返回原支付编号。 */
    @PostMapping
    public Result<PaymentVO> create(@Valid @RequestBody PaymentCreateDTO request) {
        return Result.success(paymentService.create(request));
    }

    /** 按订单编号查询支付事实和冲正结果，供后续订单服务核对。 */
    @GetMapping("/by-order/{orderNo}")
    public Result<PaymentVO> getByOrder(@PathVariable String orderNo) {
        return Result.success(paymentService.getByOrder(orderNo));
    }
}
