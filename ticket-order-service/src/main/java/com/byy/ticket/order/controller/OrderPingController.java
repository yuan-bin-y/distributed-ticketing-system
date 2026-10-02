package com.byy.ticket.order.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前阶段用于验证订单服务独立启动，业务查询随后补充。 */
@RestController
@RequestMapping("/api/orders")
public class OrderPingController {
    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("ticket-order-service", "ok");
    }

    public record PingResponse(String service, String status) {
    }
}
