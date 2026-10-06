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

import com.byy.ticket.event.vo.event.EventDetailVO;
import com.byy.ticket.event.vo.event.EventListItemVO;
import com.byy.ticket.event.vo.event.EventSessionVO;
import com.byy.ticket.event.vo.event.TicketTierVO;
import com.byy.ticket.event.vo.event.TicketPurchaseRuleVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 实现活动查询业务：只读活动数据库，组装对外展示数据和内部购票规则。
 */
@Service
@Transactional(readOnly = true)
public class EventDatabaseQueries {
    private static final String PUBLISHED = "PUBLISHED";
    private final EventMapper eventMapper;
    private final EventSessionMapper sessionMapper;
    private final TicketTierMapper tierMapper;

    /**
     * 注入活动、场次、票档 Mapper；所有查询都使用活动服务自己的数据库。
     */
    public EventDatabaseQueries(EventMapper eventMapper, EventSessionMapper sessionMapper, TicketTierMapper tierMapper) {
        this.eventMapper = eventMapper;
        this.sessionMapper = sessionMapper;
        this.tierMapper = tierMapper;
    }

    /**
     * 校验分页范围，分页读取已发布活动，再把数据库实体转换为列表 VO。
     */
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

    /**
     * 确认活动已发布后，返回活动详情 VO。
     */
    public EventDetailVO getEvent(Long eventId) {
        Event event = requirePublishedEvent(eventId);
        return new EventDetailVO(event.getId(), event.getName(), event.getCategory(), event.getCoverUrl(), event.getDescription());
    }

    /**
     * 先确认活动已发布，再查询已发布场次，批量读取启用票档并按场次分组。
     * 批量查询避免对每个场次单独查询票档；没有场次时返回空列表。
     */
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

    /**
     * 依次确认票档启用、场次已发布、活动已发布，再组装购票规则 VO。
     * 这里只读取规则；是否处于开售时间由订单服务根据自己的时钟判断。
     */
    public TicketPurchaseRuleVO getPurchaseRule(Long ticketTierId) {
        if (ticketTierId == null || ticketTierId < 1) {
            throw new IllegalArgumentException("票档ID必须大于零");
        }

        // 1. 票档必须存在且已启用。
        TicketTier tier = tierMapper.selectById(ticketTierId);
        if (tier == null || !Integer.valueOf(1).equals(tier.getEnabled())) {
            throw new ResourceNotFoundException("票档不存在或未启用");
        }

        // 2. 票档所属场次必须已发布。
        EventSession session = sessionMapper.selectById(tier.getSessionId());
        if (session == null || !PUBLISHED.equals(session.getStatus())) {
            throw new ResourceNotFoundException("场次不存在或未发布");
        }

        // 3. 所属活动同样必须已发布。
        Event event = requirePublishedEvent(session.getEventId());

        // 4. 返回规则，开售时间和购买数量由后续订单服务校验。
        return new TicketPurchaseRuleVO(
                event.getId(), session.getId(), tier.getId(), tier.getName(), tier.getPrice(),
                session.getSaleStartTime(), session.getSaleEndTime(), session.getPurchaseLimit()
        );
    }

    /**
     * 校验活动 ID 并读取已发布活动；无记录或未发布时统一抛出 404 对应的异常。
     */
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
