package com.byy.ticket.inventory.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import com.byy.ticket.inventory.service.InventoryService;
import com.byy.ticket.inventory.vo.inventory.StockReservationVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存预留的内部 HTTP 入口，供订单服务的 InventoryClient 调用。
 * 接收 JSON 或路径参数，调用本服务 InventoryService，再包装 Result；当前未增加服务间认证。
 */
@RestController
@RequestMapping("/internal/stock-reservations")
public class InternalStockReservationController {
    private final InventoryService inventoryService;

    /**
     * 通过构造方法注入库存业务接口，由 Spring 提供 InventoryServiceImpl 实例。
     */
    public InternalStockReservationController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * POST /internal/stock-reservations：按订单编号预留指定票档的数量。
     * 首次成功时可用量减少、预留量增加；相同订单和相同参数重复提交只返回原记录。
     * Valid 校验请求字段，RequestBody 把 JSON 转成 ReserveStockDTO。
     */
    @PostMapping
    public Result<StockReservationVO> reserve(@Valid @RequestBody ReserveStockDTO request) {
        return Result.success(inventoryService.reserve(request));
    }

    /**
     * POST /internal/stock-reservations/{reservationId}/confirm：把预留记录确认成已售出。
     * 预留量减少、售出量增加；重复确认已售出记录直接返回原结果。
     * 供后续支付成功流程调用，此接口本身不验证支付凭证。
     */
    @PostMapping("/{reservationId}/confirm")
    public Result<StockReservationVO> confirm(@PathVariable String reservationId) {
        return Result.success(inventoryService.confirm(reservationId));
    }

    /**
     * POST /internal/stock-reservations/{reservationId}/release：释放仍处于预留状态的记录。
     * 预留量减少、可用量恢复；重复释放直接返回原结果，已售出记录不能释放。
     */
    @PostMapping("/{reservationId}/release")
    public Result<StockReservationVO> release(@PathVariable String reservationId) {
        return Result.success(inventoryService.release(reservationId));
    }

    /**
     * GET /internal/stock-reservations/{reservationId}：查询预留记录及其当前状态。
     * 用于核对调用结果；查询本身不会改变库存或自动释放过期记录。
     */
    @GetMapping("/{reservationId}")
    public Result<StockReservationVO> getReservation(@PathVariable String reservationId) {
        return Result.success(inventoryService.getReservation(reservationId));
    }
}
