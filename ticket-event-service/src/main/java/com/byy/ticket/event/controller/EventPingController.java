package com.byy.ticket.event.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 首条链路的连通性接口，后续活动查询使用同一路由前缀。 */
@RestController
@RequestMapping("/api/events")
public class EventPingController {

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("ticket-event-service", "ok");
    }

    public record PingResponse(String service, String status) {
    }
}
