package com.byy.ticket.order.purchase;

import com.byy.ticket.order.client.StockAdmissionClient;
import com.byy.ticket.order.client.dto.*;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.observability.TraceSupport;
import java.time.*;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** MQ负责首次触发，持久化进度负责异常恢复；重投不会再次创建订单或占额度。 */
@Component
public class AsyncPurchaseWorker {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AsyncPurchaseWorker.class);
    private final PurchaseAcceptanceMapper acceptance;private final PurchaseAcceptanceTransaction transaction;private final StockAdmissionClient inventory;
    private final Clock clock;private final ObjectMapper json;private final TraceSupport traces;
    private final com.byy.ticket.order.mapper.OrderMapper orders;
    public AsyncPurchaseWorker(PurchaseAcceptanceMapper acceptance,PurchaseAcceptanceTransaction transaction,StockAdmissionClient inventory,Clock clock,ObjectMapper json,TraceSupport traces,com.byy.ticket.order.mapper.OrderMapper orders){this.acceptance=acceptance;this.transaction=transaction;this.inventory=inventory;this.clock=clock;this.json=json;this.traces=traces;this.orders=orders;}
    public void advance(String id){
        String token=UUID.randomUUID().toString().replace("-","");if(acceptance.claim(id,token)!=1)return;
        var item=acceptance.selectById(id);if(item==null||!token.equals(item.getLeaseToken()))return;
        try(var scope=traces.resume(new TraceSupport.Snapshot(item.getTraceParent(),item.getTraceState()),"order.purchase create",null)){
            try{
                if("CANCELLING".equals(item.getStatus())){cancel(item,token);return;}
                transaction.create(id,token,LocalDateTime.now(clock));
                // 到期转换会清理令牌；下一轮领取CANCELLING再执行HTTP，不跨事务持锁。
            }catch(OrderConflictException rejected){
                scope.error(rejected);acceptance.finish(id,token,"CANCELLING",summary(rejected),0);
            }catch(RuntimeException uncertain){
                scope.error(uncertain);int delay=Math.min(300,1<<Math.min(item.getAttemptCount(),8));
                acceptance.finish(id,token,item.getStatus(),"处理结果待核对："+uncertain.getClass().getSimpleName(),delay);
            }
        }
    }
    private void cancel(PurchaseAcceptance item,String token){
        // 同步入口可能已经完成同一请求；优先核对订单事实，不能取消其库存。
        if(orders.selectByRequest(item.getUserId(),item.getIdempotencyKey())!=null){acceptance.finish(item.getRequestId(),token,"CREATED",null,0);return;}
        var rule=json.readValue(item.getRuleJson(),TicketPurchaseRuleResponse.class);
        inventory.cancel(new StockReservationRequest(item.getRequestId(),rule.sessionId(),rule.ticketTierId(),item.getQuantity(),item.getExpiresAt()));
        acceptance.finish(item.getRequestId(),token,"REJECTED",item.getLastError(),0);
    }
    @Scheduled(fixedDelayString="${ticket.purchase.recovery-ms:5000}")
    public void recover(){try{for(String id:acceptance.due())try{advance(id);}catch(RuntimeException failure){log.warn("异步受理等待恢复，requestId={}",id);}}catch(RuntimeException failure){log.warn("异步受理扫描失败，下轮继续");}}
    private String summary(RuntimeException error){String value=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();return value.substring(0,Math.min(256,value.length()));}
}
