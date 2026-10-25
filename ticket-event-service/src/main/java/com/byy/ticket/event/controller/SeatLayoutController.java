package com.byy.ticket.event.controller;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.common.seat.SeatGrid;
import com.byy.ticket.event.seat.SeatLayoutService;
import org.springframework.web.bind.annotation.*;
/** 管理员配置布局，用户只读区域网格元数据。 */
@RestController public class SeatLayoutController {
    public record Configure(java.util.List<SeatGrid.Region> regions){}
    private final SeatLayoutService layouts;
    public SeatLayoutController(SeatLayoutService layouts){this.layouts=layouts;}
    @PostMapping("/api/admin/events/{eventId}/ticket-tiers/{tierId}/seat-layout") public Result<SeatLayoutService.Layout> configure(@PathVariable long eventId,@PathVariable long tierId,@RequestBody Configure body){return Result.success(layouts.configure(eventId,tierId,body.regions()));}
    @GetMapping("/api/events/{eventId}/ticket-tiers/{tierId}/seat-layout") public Result<SeatLayoutService.Layout> get(@PathVariable long eventId,@PathVariable long tierId){return Result.success(layouts.get(eventId,tierId));}
}
