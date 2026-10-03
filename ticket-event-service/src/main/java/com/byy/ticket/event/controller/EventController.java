package com.byy.ticket.event.controller;

import com.byy.ticket.common.result.PageVO;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.event.dto.event.EventPageQueryDTO;
import com.byy.ticket.event.service.EventService;
import com.byy.ticket.event.vo.event.EventDetailVO;
import com.byy.ticket.event.vo.event.EventListItemVO;
import com.byy.ticket.event.vo.event.EventSessionVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 活动服务对用户开放的查询入口：接收 HTTP 参数，调用 EventService，并包装统一响应。
 */
@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventService eventService;

    /**
     * 通过构造方法注入活动业务接口，由 Spring 提供 EventServiceImpl 实例。
     */
    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * GET /api/events：校验页码和每页条数，分页查询已发布活动。
     */
    @GetMapping
    public Result<PageVO<EventListItemVO>> listEvents(@Valid @ModelAttribute EventPageQueryDTO queryDTO) {
        return Result.success(eventService.listEvents(queryDTO));
    }

    /**
     * GET /api/events/{eventId}：根据活动 ID 查询已发布活动的详情。
     */
    @GetMapping("/{eventId}")
    public Result<EventDetailVO> getEvent(@PathVariable Long eventId) {
        return Result.success(eventService.getEvent(eventId));
    }

    /**
     * GET /api/events/{eventId}/sessions：查询活动的已发布场次和各场次启用的票档。
     */
    @GetMapping("/{eventId}/sessions")
    public Result<List<EventSessionVO>> listSessions(@PathVariable Long eventId) {
        return Result.success(eventService.listSessions(eventId));
    }
}
