package com.byy.ticket.event.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.event.service.EventService;
import com.byy.ticket.event.vo.event.TicketPurchaseRuleVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 服务间购票规则查询入口，当前通过活动服务端口直接访问。 */
@RestController
@RequestMapping("/internal/ticket-tiers")
public class InternalTicketTierController {
    private final EventService eventService;

    public InternalTicketTierController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/{ticketTierId}/purchase-rule")
    public Result<TicketPurchaseRuleVO> getPurchaseRule(@PathVariable Long ticketTierId) {
        return Result.success(eventService.getPurchaseRule(ticketTierId));
    }
}
