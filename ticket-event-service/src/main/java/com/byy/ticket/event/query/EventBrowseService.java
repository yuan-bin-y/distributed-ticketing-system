package com.byy.ticket.event.query;

import com.byy.ticket.common.result.CursorPage;
import com.byy.ticket.event.cache.EventQueryCache;
import com.byy.ticket.event.service.impl.EventDatabaseQueries;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.*;

/** 列表按需取场次与票档；仅标准首页缓存，任意深页仍受数据库回源并发保护。 */
@Service
public class EventBrowseService {
    private final EventBrowseMapper browse;private final EventDatabaseQueries events;private final EventQueryCache cache;private final ObjectMapper json;
    public EventBrowseService(EventBrowseMapper browse,EventDatabaseQueries events,EventQueryCache cache,ObjectMapper json){this.browse=browse;this.events=events;this.cache=cache;this.json=json;}
    public CursorPage<EventBrowseMapper.SessionSummary> sessions(long event,long after,int limit){
        validate(after,limit);return load("sessions:"+event,after,limit,EventBrowseMapper.SessionSummary.class,()->{events.getEvent(event);return page(browse.sessions(event,after,limit+1),limit,EventBrowseMapper.SessionSummary::id);});
    }
    public CursorPage<EventBrowseMapper.TierSummary> tiers(long event,long session,long after,int limit){
        validate(after,limit);if(session<1)throw new IllegalArgumentException("场次ID必须大于零");
        return load("tiers:"+event+":"+session,after,limit,EventBrowseMapper.TierSummary.class,()->{events.getEvent(event);return page(browse.tiers(event,session,after,limit+1),limit,EventBrowseMapper.TierSummary::id);});
    }
    private <T> CursorPage<T> load(String key,long after,int limit,Class<T> type,Supplier<CursorPage<T>> supplier){
        if(after==0&&limit==20)return cache.get("browse:"+key,json.getTypeFactory().constructParametricType(CursorPage.class,type),supplier);
        return cache.uncached(supplier);
    }
    private <T> CursorPage<T> page(List<T> data,int limit,ToLongFunction<T> id){boolean more=data.size()>limit;var records=more?data.subList(0,limit):data;return new CursorPage<>(records,records.isEmpty()?0:id.applyAsLong(records.get(records.size()-1)),more);}
    private void validate(long after,int limit){if(after<0||limit<1||limit>100)throw new IllegalArgumentException("游标不能为负，每页条数须为1至100");}
}
