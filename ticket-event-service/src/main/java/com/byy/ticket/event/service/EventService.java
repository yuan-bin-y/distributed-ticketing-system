package com.byy.ticket.event.service;

import com.byy.ticket.common.result.PageVO;
import com.byy.ticket.event.dto.event.EventPageQueryDTO;
import com.byy.ticket.event.vo.event.EventDetailVO;
import com.byy.ticket.event.vo.event.EventListItemVO;
import com.byy.ticket.event.vo.event.EventSessionVO;

import java.util.List;

/** 活动查询业务接口。 */
public interface EventService {
    PageVO<EventListItemVO> listEvents(EventPageQueryDTO queryDTO);

    EventDetailVO getEvent(Long eventId);

    List<EventSessionVO> listSessions(Long eventId);
}
