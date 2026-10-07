package com.byy.ticket.event.service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byy.ticket.event.mapper.*;
import com.byy.ticket.event.model.*;
import com.byy.ticket.event.dto.event.EventDraftDTO;
import com.byy.ticket.event.exception.EventConflictException;
import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.observability.TraceSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
/** 草稿三张表及准备参数属于Event本地事务；这里不发送HTTP。 */
@Service
public class EventDraftTransaction {
    @org.springframework.beans.factory.annotation.Autowired
    private com.byy.ticket.event.cache.EventQueryCache cache;
    private final EventMapper events;private final EventSessionMapper sessions;private final TicketTierMapper tiers;
    private final PreparationMapper preparations;
    private final TraceSupport traces;
    public EventDraftTransaction(EventMapper events,EventSessionMapper sessions,TicketTierMapper tiers,PreparationMapper preparations,TraceSupport traces){this.events=events;this.sessions=sessions;this.tiers=tiers;this.preparations=preparations;this.traces=traces;}
    /** 校验时间后保存完整草稿；任意票档失败时活动、场次和其他票档一起回滚。 */
    @Transactional
    public Long create(EventDraftDTO request,String hash){
        for(var s:request.sessions()){
            for(var time:List.of(s.startTime(),s.endTime(),s.saleStartTime(),s.saleEndTime()))
                if(time.getYear()<1000||time.getYear()>9999||time.getNano()%1_000_000!=0)throw new IllegalArgumentException("时间须在1000至9999年范围且精度最多毫秒");
            if(!s.endTime().isAfter(s.startTime())||!s.saleEndTime().isAfter(s.saleStartTime())||s.saleEndTime().isAfter(s.startTime()))
                throw new IllegalArgumentException("演出或开售时间区间无效");
        }
        var context=traces.capture();
        var event=Event.builder().name(request.name()).category(request.category()).coverUrl(request.coverUrl()).description(request.description())
                .status("DRAFT").creationKey(request.idempotencyKey()).creationHash(hash).build();events.insert(event);
        for(var s:request.sessions()){
            var session=EventSession.builder().eventId(event.getId()).name(s.name()).venueName(s.venueName()).venueAddress(s.venueAddress())
                    .startTime(s.startTime()).endTime(s.endTime()).saleStartTime(s.saleStartTime()).saleEndTime(s.saleEndTime()).purchaseLimit(s.purchaseLimit()).status("DRAFT").build();
            sessions.insert(session);
            for(var t:s.ticketTiers())tiers.insert(TicketTier.builder().sessionId(session.getId()).name(t.name()).price(t.price()).enabled(1)
                    .traceParent(context.traceParent()).traceState(context.traceState())
                    .plannedQuantity(t.totalQuantity()).preparationStatus("PENDING").preparationAttempts(0).build());
        }
        return event.getId();
    }
    /** 全部售卖票档READY才在同一本地事务发布活动和场次，未准备好返回409。 */
    @Transactional
    public void publish(Long id){
        var event=require(id);
        if("PUBLISHED".equals(event.getStatus()))return;
        if(!"DRAFT".equals(event.getStatus()))throw new EventConflictException("只有草稿可以发布");
        var all=sessions.selectList(Wrappers.<EventSession>lambdaQuery().eq(EventSession::getEventId,id));
        if(all.isEmpty())throw new EventConflictException("活动缺少场次");
        for(var session:all){
            if(!"DRAFT".equals(session.getStatus())||!session.getSaleEndTime().isAfter(LocalDateTime.now(ZoneOffset.ofHours(8))))
                throw new EventConflictException("场次状态或停售时间不允许发布");
            var stockTiers=tiers.selectList(Wrappers.<TicketTier>lambdaQuery().eq(TicketTier::getSessionId,session.getId()).eq(TicketTier::getEnabled,1));
            if(stockTiers.isEmpty()||stockTiers.stream().anyMatch(t->!"READY".equals(t.getPreparationStatus())))
                throw new EventConflictException("所有售卖票档库存准备好后才能发布");
        }
        for(var session:all){session.setStatus("PUBLISHED");sessions.updateById(session);}
        event.setStatus("PUBLISHED");events.updateById(event);
        cache.invalidateAfterCommit();
    }
    /** 管理员显式重新准备需核对票档；只改变进度，不改变原初始化快照。 */
    @Transactional public void retry(Long id){var event=require(id);if(!"DRAFT".equals(event.getStatus()))throw new EventConflictException("只有草稿可以重新准备");preparations.retryReviewed(id);}
    private Event require(Long id){var event=events.selectForUpdate(id);if(event==null)throw new ResourceNotFoundException("活动不存在");return event;}
}
