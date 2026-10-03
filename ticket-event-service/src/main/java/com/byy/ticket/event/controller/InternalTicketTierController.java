package com.byy.ticket.event.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.event.service.EventService;
import com.byy.ticket.event.vo.event.TicketPurchaseRuleVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供订单服务等调用的购票规则 HTTP 入口；Controller 本身不进行跨服务调用。
 * /internal 是内部接口约定，当前尚未增加服务间身份认证。
 */
@RestController
@RequestMapping("/internal/ticket-tiers")
public class InternalTicketTierController {
    private final EventService eventService;

    /**
     * 注入活动业务接口，收到 HTTP 请求后调用本服务的 EventServiceImpl。
     */
    public InternalTicketTierController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * GET /internal/ticket-tiers/{ticketTierId}/purchase-rule：读取票档对应的购票规则。
     * 订单服务的 EventClient 发请求到此路径；PathVariable 提取票档 ID，再由本地业务层查询数据库。
     */
    @GetMapping("/{ticketTierId}/purchase-rule")
    public Result<TicketPurchaseRuleVO> getPurchaseRule(@PathVariable Long ticketTierId) {
        return Result.success(eventService.getPurchaseRule(ticketTierId));
    }
}
