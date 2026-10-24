package com.byy.ticket.inventory.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.inventory.admission.*;
import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 仅接受 Order 服务凭证；预扣成功表示有持久化意图，不表示已经创建订单或售出。 */
@RestController
@RequestMapping("/internal/stock-admissions")
public class StockAdmissionController {
    private final StockAdmissionService service;
    public StockAdmissionController(StockAdmissionService service){this.service=service;}
    @PostMapping public Result<StockAdmission> hold(@Valid @RequestBody ReserveStockDTO request){return Result.success(service.hold(request));}
    @PostMapping("/cancel") public Result<StockAdmission> cancel(@Valid @RequestBody ReserveStockDTO request){return Result.success(service.cancel(request));}
}
