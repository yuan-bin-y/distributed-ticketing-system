package com.byy.ticket.event.controller;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.event.dto.event.EventDraftDTO;
import com.byy.ticket.event.service.EventAdminService;
import com.byy.ticket.event.vo.event.EventDraftVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
/** 管理员入口：草稿创建、准备进度、原参数重新准备、发布。 */
@RestController
@RequestMapping("/api/admin/events")
public class AdminEventController {
    private final EventAdminService events;
    public AdminEventController(EventAdminService events){this.events=events;}
    @PostMapping public Result<EventDraftVO> create(@Valid @RequestBody EventDraftDTO request){return Result.success(events.create(request));}
    @GetMapping("/{eventId}") public Result<EventDraftVO> get(@PathVariable Long eventId){return Result.success(events.get(eventId));}
    @PostMapping("/{eventId}/prepare") public Result<EventDraftVO> prepare(@PathVariable Long eventId){return Result.success(events.prepare(eventId));}
    @PostMapping("/{eventId}/publish") public Result<EventDraftVO> publish(@PathVariable Long eventId){return Result.success(events.publish(eventId));}
}
