package com.byy.ticket.inventory.controller;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.inventory.dto.inventory.InitializeStockDTO;
import com.byy.ticket.inventory.service.*;
import com.byy.ticket.inventory.vo.inventory.TicketStockVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
/** Event专用初始化及事实查询入口，由服务凭证保护。 */
@RestController
@RequestMapping("/internal/stocks/initializations")
public class InternalStockInitializationController {
    private final StockInitializationService initialization;
    private final InventoryService inventory;
    public InternalStockInitializationController(StockInitializationService initialization,InventoryService inventory){this.initialization=initialization;this.inventory=inventory;}
    @PostMapping public Result<TicketStockVO> initialize(@Valid @RequestBody InitializeStockDTO request){return Result.success(initialization.initialize(request));}
    @GetMapping("/{ticketTierId}") public Result<TicketStockVO> get(@PathVariable Long ticketTierId){return Result.success(inventory.getStock(ticketTierId));}
}
