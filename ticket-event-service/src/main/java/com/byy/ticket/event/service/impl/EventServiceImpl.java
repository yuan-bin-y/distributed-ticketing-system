package com.byy.ticket.event.service.impl;
import com.byy.ticket.event.service.EventService;
import com.byy.ticket.event.cache.EventQueryCache;
import com.byy.ticket.event.dto.event.EventPageQueryDTO;
import com.byy.ticket.event.vo.event.*;
import com.byy.ticket.common.result.PageVO;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
/** 展示查询走缓存；内部购票规则保持权威查库，等待缓存不持有数据库事务。 */
@Service
public class EventServiceImpl implements EventService {
    private final EventDatabaseQueries queries; private final EventQueryCache cache; private final ObjectMapper json;
    public EventServiceImpl(EventDatabaseQueries queries,EventQueryCache cache,ObjectMapper json){
        this.queries=queries;this.cache=cache;this.json=json;
    }
    /** 缓存前十页的已发布活动，深页使用受控数据库查询。 */
    @Override public PageVO<EventListItemVO> listEvents(EventPageQueryDTO query){
        if(query==null||query.page()<1||query.pageSize()<1||query.pageSize()>100)
            throw new IllegalArgumentException("分页参数不正确");
        // 只缓存前十页，限制分页参数导致的键空间增长。
        if(query.page()>10)return cache.uncached(()->queries.listEvents(query));
        return cache.get("page:"+query.page()+":"+query.pageSize(),
            json.getTypeFactory().constructParametricType(PageVO.class,EventListItemVO.class),
            ()->queries.listEvents(query));
    }
    /** 查询已发布活动详情，缓存不存在的活动以减少重复查库。 */
    @Override public EventDetailVO getEvent(Long id){
        validate(id);return cache.get("detail:"+id,json.getTypeFactory().constructType(EventDetailVO.class),()->queries.getEvent(id));
    }
    /** 缓存活动的已发布场次及启用票档展示数据。 */
    @Override public List<EventSessionVO> listSessions(Long id){
        validate(id);return cache.get("sessions:"+id,json.getTypeFactory().constructCollectionType(List.class,EventSessionVO.class),
            ()->queries.listSessions(id));
    }
    /** 订单下单需要权威规则，本接口不使用展示缓存。 */
    @Override public TicketPurchaseRuleVO getPurchaseRule(Long id){return queries.getPurchaseRule(id);}
    private void validate(Long id){if(id==null||id<1)throw new IllegalArgumentException("活动ID必须大于零");}
}
