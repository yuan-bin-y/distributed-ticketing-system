package com.byy.ticket.event.controller;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.event.policy.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
/** 管理员配置、发布、查看和回滚规则；沿用 Event 管理员 JWT 安全链。 */
@RestController @RequestMapping("/api/admin/events/{eventId}/purchase-policies")
public class PurchasePolicyController {
    private final PurchasePolicyService policies;
    public PurchasePolicyController(PurchasePolicyService policies){this.policies=policies;}
    @PostMapping public Result<PurchasePolicy> draft(@PathVariable long eventId,@Valid @RequestBody PurchasePolicyService.Draft request){return Result.success(policies.draft(eventId,request));}
    @GetMapping public Result<java.util.List<PurchasePolicy>> list(@PathVariable long eventId){return Result.success(policies.list(eventId));}
    @PostMapping("/{version}/publish") public Result<PurchasePolicy> publish(@PathVariable long eventId,@PathVariable long version){return Result.success(policies.publish(eventId,version));}
    @PostMapping("/{version}/rollback") public Result<PurchasePolicy> rollback(@PathVariable long eventId,@PathVariable long version){return Result.success(policies.rollback(eventId,version));}
}
