package com.byy.ticket.event.policy;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.event.mapper.EventMapper;
import com.byy.ticket.event.exception.EventConflictException;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 参数版本管理；不接收用户上传的可执行 DRL，避免把规则配置变成远程代码执行入口。 */
@Service
public class PurchasePolicyService {
    public record Draft(@Min(1) @Max(1000) int maxSessions,@Min(1) @Max(1000) int maxPerOrder,@NotBlank @Size(max=200) String note){}
    private final EventMapper events;private final PurchasePolicyMapper policies;
    public PurchasePolicyService(EventMapper events,PurchasePolicyMapper policies){this.events=events;this.policies=policies;}
    /** 在活动行锁下分配版本号，保存校验过的草稿，暂不影响购买。 */
    @Transactional public PurchasePolicy draft(long id,Draft request){
        lock(id);validate(request);var version=new PurchasePolicy(id,policies.next(id),request.maxSessions(),request.maxPerOrder(),"DRAFT",request.note());policies.insert(version);return version;
    }
    /** 原子发布并切换当前版本。历史订单与已受理请求继续使用自己的规则快照。 */
    @Transactional public PurchasePolicy publish(long id,long version){
        lock(id);var policy=policies.get(id,version);if(policy==null)throw new ResourceNotFoundException("规则版本不存在");
        if(!"DRAFT".equals(policy.status()))throw new EventConflictException("该版本已发布，不能再次覆盖当前版本；请创建新版本或回滚");
        policies.publish(id,version);policies.activate(id,version);return policies.get(id,version);
    }
    /** 回滚生成新的发布版本，不改写历史版本和已受理请求。 */
    @Transactional public PurchasePolicy rollback(long id,long version){
        lock(id);var old=policies.get(id,version);if(old==null||!"PUBLISHED".equals(old.status()))throw new ResourceNotFoundException("已发布规则版本不存在");
        var copy=new PurchasePolicy(id,policies.next(id),old.maxSessions(),old.maxPerOrder(),"PUBLISHED","回滚至版本 "+version);policies.insert(copy);policies.activate(id,copy.version());return copy;
    }
    public java.util.List<PurchasePolicy> list(long id){if(events.selectById(id)==null)throw new ResourceNotFoundException("活动不存在");return policies.list(id);}
    private void lock(long id){if(id<1||events.selectForUpdate(id)==null)throw new ResourceNotFoundException("活动不存在");}
    private void validate(Draft value){if(value==null||value.maxSessions()<1||value.maxSessions()>1000||value.maxPerOrder()<1||value.maxPerOrder()>1000||value.note()==null||value.note().isBlank()||value.note().length()>200)throw new IllegalArgumentException("规则参数无效");}
}
