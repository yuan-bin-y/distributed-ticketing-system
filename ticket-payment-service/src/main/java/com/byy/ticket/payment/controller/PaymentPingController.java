package com.byy.ticket.payment.controller;

import org.springframework.web.bind.annotation.*;

/** 独立启动和路由探测入口，不证明数据库业务、付款或订单履约已完成。 */
@RestController
@RequestMapping("/api/payments")
public class PaymentPingController {
    /** 返回服务名，便于确认请求最终到达哪个服务。 */
    @GetMapping("/ping")
    public PingResponse ping() { return new PingResponse("ticket-payment-service", "ok"); }

    /**
     * 探测响应。
     * @param service 当前服务名。
     * @param status 当前HTTP入口可用。
     */
    public record PingResponse(String service, String status) { }
}
