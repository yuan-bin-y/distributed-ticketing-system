package com.byy.ticket.event.controller;
import com.byy.ticket.common.result.*;
import com.byy.ticket.event.query.*;
import org.springframework.web.bind.annotation.*;
/** 前端先加载场次摘要，展开某场次时再加载票档；无需一次传输整棵活动数据。 */
@RestController @RequestMapping("/api/events/{eventId}/sessions")
public class EventBrowseController {
    private final EventBrowseService service;
    public EventBrowseController(EventBrowseService service){this.service=service;}
    @GetMapping("/page") public Result<CursorPage<EventBrowseMapper.SessionSummary>> sessions(@PathVariable long eventId,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit){return Result.success(service.sessions(eventId,after,limit));}
    @GetMapping("/{sessionId}/ticket-tiers/page") public Result<CursorPage<EventBrowseMapper.TierSummary>> tiers(@PathVariable long eventId,@PathVariable long sessionId,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="20") int limit){return Result.success(service.tiers(eventId,sessionId,after,limit));}
}
