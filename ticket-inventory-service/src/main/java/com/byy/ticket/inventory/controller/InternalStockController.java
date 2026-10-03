package com.byy.ticket.inventory.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.inventory.service.InventoryService;
import com.byy.ticket.inventory.vo.inventory.TicketStockVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供内部库存计数查询入口，用于核对票档的可用量、预留量和售出量。
 */
@RestController
@RequestMapping("/internal/stocks")
public class InternalStockController {
    private final InventoryService inventoryService;

    /**
     * 注入库存业务接口，把 HTTP 查询交给 InventoryServiceImpl。
     */
    public InternalStockController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * GET /internal/stocks/{ticketTierId}：读取指定票档的库存计数；不存在时返回 404。
     */
    @GetMapping("/{ticketTierId}")
    public Result<TicketStockVO> getStock(@PathVariable Long ticketTierId) {
        return Result.success(inventoryService.getStock(ticketTierId));
    }
}
