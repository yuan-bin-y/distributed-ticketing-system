package com.byy.ticket.order.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供用于手工验证服务启动和 HTTP 路径的简单连通性接口。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderPingController {
    /**
     * GET /api/orders/ping：返回服务名和固定状态 ok。
     * 此接口不查询数据库，也不代表所有下游服务均健康。
     */
    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("ticket-order-service", "ok");
    }

    /**
     * 连通性接口的固定响应，不用于检查数据库或下游服务健康。
     * 
     * @param service 当前服务名称。
     * @param status 固定状态 ok，表示该接口已执行。
     */
    public record PingResponse(String service, String status) {
    }
}
