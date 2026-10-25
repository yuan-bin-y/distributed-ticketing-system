package com.byy.ticket.order.purchase;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.order.client.dto.TicketPurchaseRuleResponse;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.mapper.*;
import com.byy.ticket.order.service.OrderTransactionService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 受理与 Outbox 同事务；消费时订单、额度、快照与受理完成标记同事务。 */
@Service
public class PurchaseAcceptanceTransaction {
    private final PurchaseAcceptanceMapper acceptances;private final PurchaseOutboxMapper outbox;
    private final PurchaseRequestGateMapper gate;private final OrderTransactionService orders;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private OrderMapper orderMapper;
    @org.springframework.beans.factory.annotation.Autowired private OrderItemMapper items;
    public PurchaseAcceptanceTransaction(PurchaseAcceptanceMapper acceptances,PurchaseOutboxMapper outbox,PurchaseRequestGateMapper gate,OrderTransactionService orders,ObjectMapper json){this.acceptances=acceptances;this.outbox=outbox;this.gate=gate;this.orders=orders;this.json=json;}
    @Transactional public PurchaseAcceptance accept(PurchaseAcceptance candidate){
        gate.lock(candidate.getUserId(),candidate.getIdempotencyKey());var old=acceptances.byKey(candidate.getUserId(),candidate.getIdempotencyKey());
        if(old!=null){same(old,candidate);return old;}
        var existing=orderMapper.selectByRequest(candidate.getUserId(),candidate.getIdempotencyKey());
        if(existing!=null){
            var item=items.selectByOrderId(existing.getId());var request=json.readValue(candidate.getRequestJson(),OrderCreateDTO.class);
            if(item==null||!request.ticketTierId().equals(item.getTicketTierId())||!request.quantity().equals(item.getQuantity())
                ||!request.seatIds().equals(com.byy.ticket.common.seat.SeatSelection.decode(item.getSeatIds())))throw new OrderConflictException("原同步订单与本次异步参数不一致");
            candidate.setStatus("CREATED");candidate.setExpiresAt(existing.getExpiresAt());
        }
        acceptances.insert(candidate);if("ACCEPTED".equals(candidate.getStatus()))outbox.insert(UUID.randomUUID().toString().replace("-",""),candidate.getRequestId());return candidate;
    }
    /** 先锁幂等入口再锁受理，保持同步、异步、恢复线程一致的加锁顺序。 */
    @Transactional public boolean create(String id,String token,LocalDateTime now){
        var snapshot=acceptances.selectById(id);if(snapshot==null)return false;
        gate.lock(snapshot.getUserId(),snapshot.getIdempotencyKey());var accepted=acceptances.lock(id);
        if(!token.equals(accepted.getLeaseToken())||accepted.getLeaseUntil()==null||!accepted.getLeaseUntil().isAfter(now))return false;
        if(!"ACCEPTED".equals(accepted.getStatus()))return false;
        if(!accepted.getExpiresAt().isAfter(now)){acceptances.finish(id,token,"CANCELLING","受理已到期，等待释放预扣",0);return false;}
        var request=json.readValue(accepted.getRequestJson(),OrderCreateDTO.class);var rule=json.readValue(accepted.getRuleJson(),TicketPurchaseRuleResponse.class);
        orders.createAccepted(id,accepted.getUserId(),request,rule,accepted.getAmount(),accepted.getAcceptedAt(),accepted.getExpiresAt());
        acceptances.finish(id,token,"CREATED",null,0);return true;
    }
    @Transactional public void replay(String eventId,long operator,String reason){
        if(reason==null||reason.isBlank()||reason.length()>200)throw new IllegalArgumentException("请填写1至200字核对原因");
        var event=outbox.lock(eventId);if(event==null)throw new ResourceNotFoundException("事件不存在");
        if(!"REVIEW_REQUIRED".equals(event.status()))throw new OrderConflictException("仅待核对事件可以重放");
        outbox.audit(eventId,operator,reason);outbox.replay(eventId);
    }
    private void same(PurchaseAcceptance old,PurchaseAcceptance next){
        // JSON列会规范化空白，比较反序列化后的 DTO，不能直接比较原字符串。
        if(!json.readValue(old.getRequestJson(),OrderCreateDTO.class).equals(json.readValue(next.getRequestJson(),OrderCreateDTO.class)))throw new OrderConflictException("同一幂等键必须保持原购买参数");
    }
}
