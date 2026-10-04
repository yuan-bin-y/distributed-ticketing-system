package com.byy.ticket.payment.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.payment.service.PaymentService;
import com.byy.ticket.payment.vo.payment.PaymentVO;
import com.byy.ticket.payment.web.PaymentIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 用户支付查询和本地模拟入口；金额来自已有支付单，必须检查用户归属。 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService paymentService;
    private final PaymentIdentityResolver identity;

    /** 注入业务能力及服务端身份解析。 */
    public PaymentController(PaymentService paymentService, PaymentIdentityResolver identity) {
        this.paymentService = paymentService;
        this.identity = identity;
    }

    /** 查询当前用户自己的支付单，其他用户的支付单统一返回不存在。 */
    @GetMapping("/{paymentNo}")
    public Result<PaymentVO> getOwned(@PathVariable String paymentNo, HttpServletRequest request) {
        return Result.success(paymentService.getOwned(identity.requireUser(request), paymentNo));
    }

    /** 显式开启模拟后才允许付款；不接收用户传入的金额或付款时间。 */
    @PostMapping("/{paymentNo}/simulate-success")
    public Result<PaymentVO> simulateSuccess(@PathVariable String paymentNo, HttpServletRequest request) {
        return Result.success(paymentService.simulateSuccess(identity.requireUser(request), paymentNo));
    }
}
