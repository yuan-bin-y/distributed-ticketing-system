package com.byy.ticket.order.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.dto.order.PaymentResultDTO;
import com.byy.ticket.order.service.OrderPaymentReceiptService;
import com.byy.ticket.order.vo.order.PaymentReceiptVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 支付服务通知入口；只在付款依据提交成功后返回接收确认，不经公共网关暴露。 */
@RestController
@RequestMapping("/internal/orders/payment-results")
public class InternalOrderPaymentController {
    private final OrderPaymentReceiptService receipts;

    /** 注入通知接收业务。 */
    public InternalOrderPaymentController(OrderPaymentReceiptService receipts) { this.receipts = receipts; }

    /** 校验线索、回查支付事实并可靠保存；失败返回错误，让支付发送方继续重试。 */
    @PostMapping
    public Result<PaymentReceiptVO> receive(@Valid @RequestBody PaymentResultDTO notification) {
        return Result.success(receipts.receive(notification));
    }
}
