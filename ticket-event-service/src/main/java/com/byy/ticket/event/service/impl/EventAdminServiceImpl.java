package com.byy.ticket.event.service.impl;
import com.byy.ticket.event.service.EventAdminService;
import com.byy.ticket.event.service.EventDraftTransaction;
import com.byy.ticket.event.service.EventPreparationWorkflow;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byy.ticket.event.dto.event.EventDraftDTO;
import com.byy.ticket.event.mapper.*;
import com.byy.ticket.event.model.*;
import com.byy.ticket.event.vo.event.EventDraftVO;
import com.byy.ticket.event.exception.EventConflictException;
import com.byy.ticket.common.exception.ResourceNotFoundException;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
/** 管理编排：验证并创建草稿，提供准备进度及发布入口。 */
@Service
public class EventAdminServiceImpl implements EventAdminService {
    private final EventMapper events;private final EventSessionMapper sessions;private final TicketTierMapper tiers;
    private final EventDraftTransaction transactions;private final EventPreparationWorkflow preparation;
    private final ObjectMapper json;private final Validator validation;
    public EventAdminServiceImpl(EventMapper events,EventSessionMapper sessions,TicketTierMapper tiers,EventDraftTransaction transactions,
            EventPreparationWorkflow preparation,ObjectMapper json,Validator validation){this.events=events;this.sessions=sessions;this.tiers=tiers;this.transactions=transactions;this.preparation=preparation;this.json=json;this.validation=validation;}
    /** 验证完整请求，以幂等键和摘要创建或返回原草稿，事务提交后由后台准备。 */
    @Override
    public EventDraftVO create(EventDraftDTO request){
        if(request==null||!validation.validate(request).isEmpty())throw new IllegalArgumentException("草稿参数无效");
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
        var existing=events.selectByCreationKey(request.idempotencyKey());
        if(existing!=null)return repeat(existing,hash);
        Long id;
        try{id=transactions.create(request,hash);}catch(DuplicateKeyException conflict){
            existing=events.selectByCreationKey(request.idempotencyKey());
            if(existing!=null)return repeat(existing,hash);
            throw new EventConflictException("同一场次票档名称不能重复");
        }
        // 草稿保存成功返回即可；后台按持久化参数自动准备，HTTP不占草稿事务。
        return get(id);
    }
    private EventDraftVO repeat(Event event,String hash){if(!hash.equals(event.getCreationHash()))throw new EventConflictException("同一创建幂等键必须使用相同参数");return get(event.getId());}
    /** 管理查询包含草稿与准备进度；普通用户入口不返回这些未发布记录。 */
    @Override
    public EventDraftVO get(Long id){
        if(id==null||id<1)throw new IllegalArgumentException("活动ID必须大于零");
        var event=events.selectById(id);if(event==null)throw new ResourceNotFoundException("活动不存在");
        var all=sessions.selectList(Wrappers.<EventSession>lambdaQuery().eq(EventSession::getEventId,id).orderByAsc(EventSession::getId));
        var result=all.stream().map(s->new EventDraftVO.Session(s.getId(),s.getName(),s.getStatus(),s.getStartTime(),s.getEndTime(),s.getSaleStartTime(),s.getSaleEndTime(),s.getPurchaseLimit(),
                tiers.selectList(Wrappers.<TicketTier>lambdaQuery().eq(TicketTier::getSessionId,s.getId()).orderByAsc(TicketTier::getId)).stream()
                        .map(t->new EventDraftVO.Tier(t.getId(),t.getName(),t.getPrice(),t.getPlannedQuantity(),t.getPreparationStatus(),t.getPreparationAttempts(),t.getPreparationError())).toList())).toList();
        return new EventDraftVO(id,event.getName(),event.getStatus(),result);
    }
    /** 管理请求至多推进一个票档，避免批量HTTP占用请求线程；其他票档由后台继续处理。 */
    @Override
    public EventDraftVO prepare(Long id){
        transactions.retry(id);
        for(var session:get(id).sessions())for(var tier:session.ticketTiers())if("PENDING".equals(tier.preparationStatus())){
            preparation.advance(tier.ticketTierId());return get(id);
        }
        return get(id);
    }
    /** 发布条件及活动/场次更新交给独立本地事务，返回最新管理视图。 */
    @Override
    public EventDraftVO publish(Long id){transactions.publish(id);return get(id);}
}
