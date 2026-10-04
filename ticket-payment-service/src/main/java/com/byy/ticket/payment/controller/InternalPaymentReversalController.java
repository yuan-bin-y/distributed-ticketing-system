package com.byy.ticket.payment.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.payment.dto.payment.PaymentReversalDTO;
import com.byy.ticket.payment.service.PaymentService;
import com.byy.ticket.payment.vo.payment.PaymentReversalVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 订单服务决定无法履约后发起的模拟全额冲正入口，不提供公共用户冲正接口。 */
@RestController
@RequestMapping("/internal/payment-reversals")
public class InternalPaymentReversalController {
    private final PaymentService paymentService;

    /** 注入支付业务接口。 */
    public InternalPaymentReversalController(PaymentService paymentService) { this.paymentService = paymentService; }

    /** 对已成功支付的支付单模拟全额冲正；相同命令幂等返回原记录。 */
    @PostMapping
    public Result<PaymentReversalVO> reverse(@Valid @RequestBody PaymentReversalDTO request) {
        return Result.success(paymentService.reverse(request));
    }
}
