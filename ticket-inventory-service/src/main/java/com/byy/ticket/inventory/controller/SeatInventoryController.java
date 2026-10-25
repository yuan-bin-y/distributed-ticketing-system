package com.byy.ticket.inventory.controller;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.inventory.seat.SeatInventoryService;
import org.springframework.web.bind.annotation.*;
/** 初始化使用Event服务身份，动态查询使用Order服务身份，均不向公网暴露。 */
@RestController public class SeatInventoryController {
    private final SeatInventoryService seats;
    private final com.byy.ticket.inventory.admission.StockAdmissionService admissions;
    public SeatInventoryController(SeatInventoryService seats,com.byy.ticket.inventory.admission.StockAdmissionService admissions){this.seats=seats;this.admissions=admissions;}
    @PostMapping("/internal/stocks/initializations/{tierId}/seats") public Result<SeatInventoryService.Layout> initialize(@PathVariable long tierId,@RequestBody SeatInventoryService.Layout layout){
        if(!admissions.enabled())throw new com.byy.ticket.inventory.exception.InventoryConflictException("选座需要启用Redis库存预扣");
        var result=seats.initialize(tierId,layout);admissions.rebuild(tierId);return Result.success(result);
    }
    @GetMapping("/internal/stocks/{tierId}/seats") public Result<SeatInventoryService.Changes> changes(@PathVariable long tierId,@RequestParam String region,@RequestParam(defaultValue="1") int rowStart,@RequestParam(defaultValue="10") int rowCount,@RequestParam(defaultValue="1") int columnStart,@RequestParam(defaultValue="20") int columnCount,@RequestParam(defaultValue="0") long since,@RequestParam(defaultValue="0") long through,@RequestParam(defaultValue="0") int after,@RequestParam(defaultValue="200") int limit){
        if(columnCount>50)throw new IllegalArgumentException("单次列视窗最多50列");return Result.success(seats.changes(tierId,region,rowStart,rowCount,columnStart,columnCount,since,through,after,limit));
    }
}
