package com.byy.ticket.event.service;
import com.byy.ticket.event.mapper.*;
import com.byy.ticket.event.client.InventoryInitializationClient;
import com.byy.ticket.observability.TraceSupport;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.slf4j.*;
/** 短SQL领取、事务外HTTP、令牌提交结果；失败进度存在数据库，重启后可恢复。 */
@Service
public class EventPreparationWorkflow {
    private static final Logger log=LoggerFactory.getLogger(EventPreparationWorkflow.class);
    private final PreparationMapper preparation;private final TicketTierMapper tiers;private final InventoryInitializationClient inventory;
    private final TraceSupport traces;
    public EventPreparationWorkflow(PreparationMapper preparation,TicketTierMapper tiers,InventoryInitializationClient inventory,TraceSupport traces){this.preparation=preparation;this.tiers=tiers;this.inventory=inventory;this.traces=traces;}
    /** 单条失败不阻断其余任务，每次恢复保留独立traceId。 */
    public void recoverDue(){for(Long id:preparation.selectDue()){
        try{advance(id,false);}catch(RuntimeException failure){log.error("库存准备恢复暂未完成，ticketTierId={}",id,failure);}
    }}
    /** 领取一条票档准备任务，核对远程事实后按领取令牌保存结果。 */
    public void advance(Long id){
        advance(id,true);
    }
    private void advance(Long id,boolean interactive){
        String token=UUID.randomUUID().toString().replace("-","");
        if(preparation.claim(id,token)!=1)return;
        var tier=tiers.selectById(id);if(tier==null||!token.equals(tier.getPreparationToken()))return;
        // 显式重新准备沿用本次管理请求；后台任务恢复草稿创建时保存的上下文。
        var parent=interactive?traces.capture():new TraceSupport.Snapshot(tier.getTraceParent(),tier.getTraceState());
        try(var scope=traces.resume(parent,"event.stock initialize",null)){
        try{
            inventory.initialize(id,tier.getSessionId(),tier.getPlannedQuantity());
            preparation.finish(id,token,"READY",null,0);
        }catch(InventoryInitializationClient.InitializationRejected rejected){
            scope.error(rejected);
            preparation.finish(id,token,"REVIEW_REQUIRED",rejected.getMessage(),0);
        }catch(RuntimeException uncertain){
            scope.error(uncertain);
            // 包括网络失败和本地结果保存失败，不推断库存未创建，不更换票档ID或数量。
            int delay=Math.min(30,1<<Math.min(Math.max(tier.getPreparationAttempts()-1,0),5));
            preparation.finish(id,token,"PENDING","初始化结果待核对："+uncertain.getClass().getSimpleName(),delay);
        }
        }
    }
}
