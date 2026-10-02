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

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public Result<PageVO<EventListItemVO>> listEvents(@Valid @ModelAttribute EventPageQueryDTO queryDTO) {
        return Result.success(eventService.listEvents(queryDTO));
    }

    @GetMapping("/{eventId}")
    public Result<EventDetailVO> getEvent(@PathVariable Long eventId) {
        return Result.success(eventService.getEvent(eventId));
    }

    @GetMapping("/{eventId}/sessions")
    public Result<List<EventSessionVO>> listSessions(@PathVariable Long eventId) {
        return Result.success(eventService.listSessions(eventId));
    }
}
