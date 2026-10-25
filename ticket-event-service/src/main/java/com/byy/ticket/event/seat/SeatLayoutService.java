package com.byy.ticket.event.seat;
import com.byy.ticket.common.seat.SeatGrid;
import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.event.mapper.*;
import com.byy.ticket.event.exception.EventConflictException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;
/** Event保存不可变座位布局，动态可用、锁定、售出状态由Inventory负责。 */
@Service public class SeatLayoutService {
    public record Layout(long ticketTierId,long sessionId,String layoutHash,List<SeatGrid.Region> regions){}
    private final SeatLayoutMapper layouts;private final EventMapper events;private final EventSessionMapper sessions;private final TicketTierMapper tiers;private final ObjectMapper json;
    public SeatLayoutService(SeatLayoutMapper layouts,EventMapper events,EventSessionMapper sessions,TicketTierMapper tiers,ObjectMapper json){this.layouts=layouts;this.events=events;this.sessions=sessions;this.tiers=tiers;this.json=json;}
    /** 草稿阶段一次配置；同内容重试幂等，不允许改变已保存布局。 */
    @Transactional public Layout configure(long event,long tier,List<SeatGrid.Region> regions){
        var owner=events.selectForUpdate(event);if(owner==null)throw new ResourceNotFoundException("活动不存在");
        if(!"DRAFT".equals(owner.getStatus()))throw new EventConflictException("只有草稿可以配置选座");
        var stockTier=tiers.selectById(tier);var session=stockTier==null?null:sessions.selectById(stockTier.getSessionId());
        if(session==null||session.getEventId()!=event)throw new ResourceNotFoundException("活动票档不存在");
        var normalized=SeatGrid.validate(regions);if(stockTier.getPlannedQuantity()==null||SeatGrid.total(normalized)!=stockTier.getPlannedQuantity())throw new IllegalArgumentException("座位总数必须等于票档计划库存");
        String hash=SeatGrid.hash(normalized);var old=layouts.get(tier);
        if(old!=null){if(!hash.equals(old.layoutHash()))throw new EventConflictException("已配置布局不可修改，请重新创建草稿");return view(old);}
        var saved=new SeatLayoutMapper.Layout(tier,session.getId(),hash,json.writeValueAsString(normalized));layouts.insert(saved);layouts.prepare(tier);return view(saved);
    }
    /** 静态布局只有区域尺寸，不一次返回所有座位；前端根据视窗生成当前可见网格。 */
    public Layout get(long event,long tier){
        var owner=events.selectById(event);var layout=layouts.get(tier);var session=layout==null?null:sessions.selectById(layout.sessionId());
        if(owner==null||!"PUBLISHED".equals(owner.getStatus())||session==null||session.getEventId()!=event||!"PUBLISHED".equals(session.getStatus()))throw new ResourceNotFoundException("选座布局不存在或尚未发布");return view(layout);
    }
    public Layout preparation(long tier){var value=layouts.get(tier);return value==null?null:view(value);}
    private Layout view(SeatLayoutMapper.Layout value){return new Layout(value.ticketTierId(),value.sessionId(),value.layoutHash(),json.readValue(value.regionsJson(),new TypeReference<List<SeatGrid.Region>>(){}));}
}
