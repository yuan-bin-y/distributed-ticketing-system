package com.byy.ticket.event.controller;

import com.byy.ticket.common.result.*;
import com.byy.ticket.event.query.*;

import org.springframework.web.bind.annotation.*;

/** 前端先加载场次摘要，展开某场次时再加载票档；无需一次传输整棵活动数据。 */
@RestController
@RequestMapping("/api/events/{eventId}/sessions")
public class EventBrowseController {
    private final EventBrowseService service;
    private final com.byy.ticket.event.cache.DisplayQueryExecutor display;

    public EventBrowseController(
            EventBrowseService service, com.byy.ticket.event.cache.DisplayQueryExecutor display) {
        this.service = service;
        this.display = display;
    }

    @GetMapping("/page")
    public org.springframework.web.context.request.async.DeferredResult<
                    Result<CursorPage<EventBrowseMapper.SessionSummary>>>
            sessions(
                    @PathVariable long eventId,
                    @RequestParam(defaultValue = "0") long after,
                    @RequestParam(defaultValue = "20") int limit) {
        return display.submit(
                "browse:sessions:" + eventId + ":" + after + ":" + limit,
                () -> service.sessions(eventId, after, limit));
    }

    @GetMapping("/{sessionId}/ticket-tiers/page")
    public org.springframework.web.context.request.async.DeferredResult<
                    Result<CursorPage<EventBrowseMapper.TierSummary>>>
            tiers(
                    @PathVariable long eventId,
                    @PathVariable long sessionId,
                    @RequestParam(defaultValue = "0") long after,
                    @RequestParam(defaultValue = "20") int limit) {
        return display.submit(
                "browse:tiers:" + eventId + ":" + sessionId + ":" + after + ":" + limit,
                () -> service.tiers(eventId, sessionId, after, limit));
    }
}
