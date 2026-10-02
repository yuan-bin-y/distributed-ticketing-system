package com.byy.ticket.event.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.result.PageVO;
import com.byy.ticket.event.dto.event.EventPageQueryDTO;
import com.byy.ticket.event.mapper.EventMapper;
import com.byy.ticket.event.mapper.EventSessionMapper;
import com.byy.ticket.event.mapper.TicketTierMapper;
import com.byy.ticket.event.model.Event;
import com.byy.ticket.event.model.EventSession;
import com.byy.ticket.event.model.TicketTier;
import com.byy.ticket.event.service.EventService;
import com.byy.ticket.event.vo.event.EventDetailVO;
import com.byy.ticket.event.vo.event.EventListItemVO;
import com.byy.ticket.event.vo.event.EventSessionVO;
import com.byy.ticket.event.vo.event.TicketTierVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 活动查询业务实现，实体转换为 VO 后再返回。 */
@Service
@Transactional(readOnly = true)
public class EventServiceImpl implements EventService {
    private static final String PUBLISHED = "PUBLISHED";
    private final EventMapper eventMapper;
    private final EventSessionMapper sessionMapper;
    private final TicketTierMapper tierMapper;

    public EventServiceImpl(EventMapper eventMapper, EventSessionMapper sessionMapper, TicketTierMapper tierMapper) {
        this.eventMapper = eventMapper;
        this.sessionMapper = sessionMapper;
        this.tierMapper = tierMapper;
    }

    @Override
    public PageVO<EventListItemVO> listEvents(EventPageQueryDTO queryDTO) {
        if (queryDTO.page() < 1 || queryDTO.pageSize() < 1 || queryDTO.pageSize() > 100) {
            throw new IllegalArgumentException("页码必须大于等于1，每页数量必须在1到100之间");
        }
        var query = Wrappers.<Event>lambdaQuery()
                .select(Event::getId, Event::getName, Event::getCategory, Event::getCoverUrl)
                .eq(Event::getStatus, PUBLISHED)
                .orderByDesc(Event::getCreatedAt).orderByDesc(Event::getId);
        Page<Event> page = eventMapper.selectPage(new Page<>(queryDTO.page(), queryDTO.pageSize()), query);
        List<EventListItemVO> records = page.getRecords().stream()
                .map(event -> new EventListItemVO(event.getId(), event.getName(), event.getCategory(), event.getCoverUrl()))
                .toList();
        return new PageVO<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public EventDetailVO getEvent(Long eventId) {
        Event event = requirePublishedEvent(eventId);
        return new EventDetailVO(event.getId(), event.getName(), event.getCategory(), event.getCoverUrl(), event.getDescription());
    }

    @Override
    public List<EventSessionVO> listSessions(Long eventId) {
        requirePublishedEvent(eventId);
        List<EventSession> sessions = sessionMapper.selectList(Wrappers.<EventSession>lambdaQuery()
                .eq(EventSession::getEventId, eventId).eq(EventSession::getStatus, PUBLISHED)
                .orderByAsc(EventSession::getStartTime).orderByAsc(EventSession::getId));
        if (sessions.isEmpty()) {
            return List.of();
        }
        // 批量查询所有场次的票档，避免逐场查询数据库。
        List<Long> sessionIds = sessions.stream().map(EventSession::getId).toList();
        List<TicketTier> tiers = tierMapper.selectList(Wrappers.<TicketTier>lambdaQuery()
                .in(TicketTier::getSessionId, sessionIds).eq(TicketTier::getEnabled, 1)
                .orderByAsc(TicketTier::getPrice).orderByAsc(TicketTier::getId));
        Map<Long, List<TicketTierVO>> tiersBySession = tiers.stream().collect(Collectors.groupingBy(
                TicketTier::getSessionId,
                Collectors.mapping(tier -> new TicketTierVO(tier.getId(), tier.getName(), tier.getPrice()), Collectors.toList())));
        return sessions.stream().map(session -> new EventSessionVO(
                session.getId(), session.getName(), session.getVenueName(), session.getVenueAddress(),
                session.getStartTime(), session.getEndTime(), session.getSaleStartTime(), session.getSaleEndTime(),
                session.getPurchaseLimit(), tiersBySession.getOrDefault(session.getId(), List.of()))).toList();
    }

    private Event requirePublishedEvent(Long eventId) {
        if (eventId == null || eventId < 1) {
            throw new IllegalArgumentException("活动ID必须大于零");
        }
        Event event = eventMapper.selectOne(Wrappers.<Event>lambdaQuery()
                .eq(Event::getId, eventId).eq(Event::getStatus, PUBLISHED));
        if (event == null) {
            throw new ResourceNotFoundException("活动不存在或未发布");
        }
        return event;
    }
}
