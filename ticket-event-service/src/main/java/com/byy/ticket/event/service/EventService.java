package com.byy.ticket.event.service;

import com.byy.ticket.common.result.PageVO;
import com.byy.ticket.event.dto.event.EventPageQueryDTO;
import com.byy.ticket.event.vo.event.EventDetailVO;
import com.byy.ticket.event.vo.event.EventListItemVO;
import com.byy.ticket.event.vo.event.EventSessionVO;
import com.byy.ticket.event.vo.event.TicketPurchaseRuleVO;

import java.util.List;

/**
 * 定义活动服务的业务能力；Controller 调用此接口，实际逻辑由 EventServiceImpl 执行。
 */
public interface EventService {
    /**
     * 分页查询已发布活动，只返回活动列表需要的字段。
     */
    PageVO<EventListItemVO> listEvents(EventPageQueryDTO queryDTO);

    /**
     * 查询指定已发布活动的详情；不存在或未发布时抛出资源不存在异常。
     */
    EventDetailVO getEvent(Long eventId);

    /**
     * 查询活动的已发布场次，并在每个场次中附带启用的票档。
     */
    List<EventSessionVO> listSessions(Long eventId);

    /**
     * 读取指定票档及所属场次、活动的规则，供订单服务校验开售时间、数量并计算金额。
     * 规则响应不包含可用库存，也不在此方法中执行库存预留。
     */
    TicketPurchaseRuleVO getPurchaseRule(Long ticketTierId);
}
