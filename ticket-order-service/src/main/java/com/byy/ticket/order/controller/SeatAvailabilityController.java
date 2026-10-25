package com.byy.ticket.order.controller;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.client.*;
import org.springframework.web.bind.annotation.*;
/** 用户只读取已发布票档的座位；动态状态是选座提示，最终以预扣结果为准。 */
@RestController @RequestMapping("/api/orders/ticket-tiers/{tierId}/seats")
public class SeatAvailabilityController {
    private final StockAdmissionClient inventory;private final EventCallGuard events;
    public SeatAvailabilityController(StockAdmissionClient inventory,EventCallGuard events){this.inventory=inventory;this.events=events;}
    @GetMapping public Result<StockAdmissionClient.SeatChanges> changes(@PathVariable long tierId,@RequestParam String region,@RequestParam(defaultValue="1") int rowStart,@RequestParam(defaultValue="10") int rowCount,@RequestParam(defaultValue="1") int columnStart,@RequestParam(defaultValue="20") int columnCount,@RequestParam(defaultValue="0") long since,@RequestParam(defaultValue="0") long through,@RequestParam(defaultValue="0") int after,@RequestParam(defaultValue="200") int limit){
        events.getPurchaseRule(tierId);return Result.success(inventory.seats(tierId,region,rowStart,rowCount,columnStart,columnCount,since,through,after,limit));
    }
}
