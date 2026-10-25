package com.byy.ticket.inventory.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.inventory.admission.StockAdmissionService;

import org.springframework.web.bind.annotation.*;

/** Order在接受新订单前核对协议；既有预留仍通过原幂等接口恢复。 */
@RestController
@RequestMapping("/internal/inventory/capabilities")
public class InventoryCapabilityController {
    public record Capability(int protocolVersion, boolean stockAdmission, boolean selectedSeats) {}

    private final StockAdmissionService admission;

    public InventoryCapabilityController(StockAdmissionService admission) {
        this.admission = admission;
    }

    @GetMapping
    public Result<Capability> get() {
        return Result.success(new Capability(1, admission.enabled(), admission.enabled()));
    }
}
