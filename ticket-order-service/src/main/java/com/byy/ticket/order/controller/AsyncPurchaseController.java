package com.byy.ticket.order.controller;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.purchase.*;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.web.OrderIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
/** 用户受理查询隔离与管理员重放入口；均经过 Order JWT 校验。 */
@RestController @RequestMapping("/api/orders")
public class AsyncPurchaseController {
    private final AsyncPurchaseService service;private final OrderIdentityResolver identity;private final PurchaseOutboxMapper outbox;private final PurchaseAcceptanceTransaction transaction;
    public AsyncPurchaseController(AsyncPurchaseService service,OrderIdentityResolver identity,PurchaseOutboxMapper outbox,PurchaseAcceptanceTransaction transaction){this.service=service;this.identity=identity;this.outbox=outbox;this.transaction=transaction;}
    @PostMapping("/requests") public ResponseEntity<Result<AsyncPurchaseService.Accepted>> accept(@Valid @RequestBody OrderCreateDTO body,HttpServletRequest request){return ResponseEntity.accepted().body(Result.success(service.accept(identity.requireUser(request),body)));}
    @GetMapping("/requests/{requestId}") public Result<AsyncPurchaseService.Accepted> get(@PathVariable String requestId,HttpServletRequest request){return Result.success(service.get(identity.requireUser(request),requestId));}
    @GetMapping("/admin/purchase-outbox/review") public Result<java.util.List<PurchaseOutboxMapper.Event>> review(){return Result.success(outbox.reviewed());}
    public record Replay(String reason){}
    @PostMapping("/admin/purchase-outbox/{eventId}/replay") public Result<Void> replay(@PathVariable String eventId,@RequestBody Replay body,HttpServletRequest request){transaction.replay(eventId,identity.requireUser(request),body.reason());return Result.success(null);}
}
