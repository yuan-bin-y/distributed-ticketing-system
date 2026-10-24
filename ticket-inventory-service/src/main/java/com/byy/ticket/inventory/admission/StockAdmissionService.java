package com.byy.ticket.inventory.admission;

import com.byy.ticket.inventory.dto.inventory.ReserveStockDTO;
import com.byy.ticket.inventory.exception.InventoryConflictException;
import com.byy.ticket.inventory.mapper.TicketStockMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** Redis 先筛掉无票请求，成功意图落库后才返回；失败、过期和重建均以库存本地事实为准。 */
@Service
public class StockAdmissionService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StockAdmissionService.class);
    private final TicketStockMapper stocks;
    private final AdmissionMapper admissions;
    private final RedisStockLedger ledger;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final boolean enabled;
    public StockAdmissionService(TicketStockMapper stocks,AdmissionMapper admissions,RedisStockLedger ledger,
            PlatformTransactionManager manager,Clock clock,MeterRegistry metrics,
            @Value("${ticket.stock-admission.enabled:false}") boolean enabled) {
        this.stocks=stocks;this.admissions=admissions;this.ledger=ledger;this.clock=clock;this.metrics=metrics;this.enabled=enabled;
        transaction=new TransactionTemplate(manager);transaction.setTimeout(10);
    }
    public boolean enabled(){return enabled;}

    /** 升级前已存在但尚未执行预留的订单也经过准入，已有事实直接由原幂等逻辑核对。 */
    public void ensureForLegacy(ReserveStockDTO request){if(enabled&&admissions.find(request.orderId())==null)hold(request);}

    /** Lua 无票直接拒绝，不进入写事务；代次变化或响应丢失时使用相同编号重新核对。 */
    public StockAdmission hold(ReserveStockDTO request) {
        if(!enabled)throw new InventoryConflictException("Redis 库存预扣未启用");
        validate(request);
        var candidate=new StockAdmission(request.orderId(),request.ticketTierId(),request.sessionId(),request.quantity(),request.expiresAt(),"HELD");
        String fingerprint=RedisStockLedger.fingerprint(candidate);
        String claim=ledger.hold(candidate.ticketTierId(),candidate.orderNo(),candidate.quantity(),fingerprint);
        if("NOT_READY".equals(claim)){rebuild(candidate.ticketTierId());claim=ledger.hold(candidate.ticketTierId(),candidate.orderNo(),candidate.quantity(),fingerprint);}
        if("SOLD_OUT".equals(claim)){metrics.counter("ticket.inventory.prehold","outcome","sold_out").increment();throw new InventoryConflictException("可用库存不足");}
        if(claim==null||!claim.endsWith("|"+fingerprint))throw new InventoryConflictException("同一预扣编号的参数不一致或账本尚未准备好");
        String epoch=claim.substring(0,claim.indexOf('|'));
        try {
            var result=transaction.execute(status->{
                var stock=stocks.selectForUpdate(candidate.ticketTierId());
                if(stock==null||!candidate.sessionId().equals(stock.getSessionId()))throw new InventoryConflictException("票档库存与场次不匹配");
                var old=admissions.find(candidate.orderNo());
                if(old!=null){requireSame(old,candidate);return old;}
                if(!epoch.equals(admissions.epoch(candidate.ticketTierId())))throw new InventoryConflictException("预扣代次已变化，请使用相同编号重试");
                if(!candidate.expiresAt().isAfter(LocalDateTime.now(clock)))throw new InventoryConflictException("预扣已到期");
                // Redis 异步复制切换可能丢失扣量；持久化准入总额也不能超过 MySQL 可用量。
                long held=admissions.heldQuantity(candidate.ticketTierId());
                if(held+candidate.quantity()>stock.getAvailableQuantity())throw new InventoryConflictException("可用库存不足，预扣账本需核对");
                admissions.insert(candidate);
                var saved=admissions.find(candidate.orderNo());requireSame(saved,candidate);
                return saved;
            });
            if(!"HELD".equals(result.status())&&!"APPLIED".equals(result.status()))throw new InventoryConflictException("该预扣已取消，不能重新占票");
            metrics.counter("ticket.inventory.prehold","outcome","accepted").increment();return result;
        } catch(RuntimeException failure) {
            // 不直接 INCR：提交结果可能未知。新代次从已提交事实重建，并废止尚未落库的旧请求。
            try{rebuild(candidate.ticketTierId());}catch(RuntimeException repair){failure.addSuppressed(repair);}
            throw failure;
        }
    }

    /** 在正式库存事务中调用；库存行必须已锁定，终态取消记录阻止迟到的正式预留。 */
    public void applyWithinTransaction(ReserveStockDTO request) {
        var old=admissions.find(request.orderId());
        if(old==null){if(enabled)throw new InventoryConflictException("正式预留缺少持久化预扣");return;}
        requireSame(old,new StockAdmission(request.orderId(),request.ticketTierId(),request.sessionId(),request.quantity(),request.expiresAt(),"HELD"));
        if(!old.expiresAt().equals(request.expiresAt()))throw new InventoryConflictException("正式预留必须沿用首次预扣到期时间");
        if(!"HELD".equals(old.status())||!old.expiresAt().isAfter(LocalDateTime.now(clock)))throw new InventoryConflictException("预扣已取消或过期");
        admissions.state(old.orderNo(),"APPLIED");admissions.dirty(old.ticketTierId());
    }

    /** 在释放库存的同一本地事务保存终态，下一次重建自然把数量归还 Redis。 */
    public void releasedWithinTransaction(String orderNo,long tier) {
        if(admissions.find(orderNo)!=null){admissions.state(orderNo,"RELEASED");admissions.dirty(tier);}
    }

    /** 先持久化取消墓碑再重建。取消与正式预留共用库存行锁，不能发生取消后迟到扣库存。 */
    public StockAdmission cancel(ReserveStockDTO request) {
        validate(request);
        var result=transaction.execute(status->{
            if(stocks.selectForUpdate(request.ticketTierId())==null)throw new InventoryConflictException("库存不存在");
            var candidate=new StockAdmission(request.orderId(),request.ticketTierId(),request.sessionId(),request.quantity(),request.expiresAt(),"CANCELLED");
            admissions.insert(candidate);var old=admissions.find(request.orderId());requireSame(old,candidate);
            if("APPLIED".equals(old.status()))throw new InventoryConflictException("库存已正式预留，须通过正式释放接口处理");
            if("HELD".equals(old.status()))admissions.state(old.orderNo(),"CANCELLED");
            admissions.dirty(old.ticketTierId());return admissions.find(old.orderNo());
        });
        try{rebuild(request.ticketTierId());}catch(RuntimeException failure){log.warn("取消已落库，Redis 数量等待恢复，tier={}",request.ticketTierId());}
        return result;
    }

    /** 缺失账本或定期核对时重建。SQL 行锁、Redis 新代次和持久化待落实记录共同隔离旧请求。 */
    public void rebuild(long tier) {
        transaction.executeWithoutResult(status->{
            var stock=stocks.selectForUpdate(tier);if(stock==null)throw new InventoryConflictException("库存不存在");
            var pending=admissions.pending(tier);
            for(var item:pending)if(!item.expiresAt().isAfter(LocalDateTime.now(clock)))admissions.state(item.orderNo(),"CANCELLED");
            pending=admissions.pending(tier);
            long held=pending.stream().mapToLong(StockAdmission::quantity).sum();
            if(held>stock.getAvailableQuantity())throw new IllegalStateException("待落实预扣超过可用库存，停止准入并核对");
            String epoch=UUID.randomUUID().toString().replace("-","");
            admissions.rebuilt(tier,epoch);
            ledger.reset(tier,epoch,(int)(stock.getAvailableQuantity()-held),pending);
            // 若 SQL 提交失败，Redis 代次也不能被接受：hold 的落库检查会检测不匹配并重新核对。
            metrics.counter("ticket.inventory.prehold.rebuild").increment();
        });
    }
    /** 超时只触发核对，不依赖 TTL 删除账本。失败时保留原记录并在下一轮继续。 */
    @Scheduled(fixedDelayString="${ticket.stock-admission.reconcile-ms:5000}")
    public void reconcile(){if(enabled)for(long tier:admissions.due())try{rebuild(tier);}catch(RuntimeException failure){log.warn("库存预扣账本待恢复，tier={}",tier);}}

    private void requireSame(StockAdmission left,StockAdmission right) {
        if(left==null||!left.ticketTierId().equals(right.ticketTierId())||!RedisStockLedger.fingerprint(left).equals(RedisStockLedger.fingerprint(right)))
            throw new InventoryConflictException("同一预扣编号必须使用原场次、票档、数量和到期时间");
    }
    private void validate(ReserveStockDTO request) {
        if(request==null||request.orderId()==null||!request.orderId().matches("[A-Za-z0-9_-]{1,64}")||request.ticketTierId()==null||request.ticketTierId()<1
            ||request.sessionId()==null||request.sessionId()<1||request.quantity()==null||request.quantity()<1||request.expiresAt()==null
            ||request.expiresAt().getYear()<1000||request.expiresAt().getYear()>9999||request.expiresAt().getNano()%1_000_000!=0)
            throw new IllegalArgumentException("库存预扣参数无效");
    }
}
