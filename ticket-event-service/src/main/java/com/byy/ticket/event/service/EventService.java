package com.byy.ticket.event.service;

import com.byy.ticket.common.result.PageVO;
import com.byy.ticket.event.dto.event.EventPageQueryDTO;
import com.byy.ticket.event.vo.event.EventDetailVO;
import com.byy.ticket.event.vo.event.EventListItemVO;
import com.byy.ticket.event.vo.event.EventSessionVO;
import com.byy.ticket.event.vo.event.TicketPurchaseRuleVO;

import java.util.List;

/** 活动查询业务接口。 */
public interface EventService {
    PageVO<EventListItemVO> listEvents(EventPageQueryDTO queryDTO);

    EventDetailVO getEvent(Long eventId);

    List<EventSessionVO> listSessions(Long eventId);

    /** 查询启用票档及其已发布场次、活动的购票规则。 */
    TicketPurchaseRuleVO getPurchaseRule(Long ticketTierId);
}
