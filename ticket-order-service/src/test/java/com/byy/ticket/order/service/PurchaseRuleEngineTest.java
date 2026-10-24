package com.byy.ticket.order.service;
import com.byy.ticket.order.rules.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PurchaseRuleEngineTest {
    static final PurchaseRuleEngine ENGINE=new PurchaseRuleEngine();
    static final LocalDateTime NOW=LocalDateTime.of(2026,10,24,12,0);
    PurchaseFacts facts(int quantity,int used,int sessions,boolean existing,int perOrder,LocalDateTime now){return new PurchaseFacts(quantity,used,sessions,existing,2,3,perOrder,now,NOW,NOW.plusHours(1));}
    @Test void cumulativeAndDistinctSessionRules(){
        assertTrue(ENGINE.evaluate(facts(1,1,3,true,2,NOW)).isEmpty());
        assertTrue(ENGINE.evaluate(facts(2,1,0,false,2,NOW)).stream().anyMatch(v->v.contains("累计购买数量")));
        assertTrue(ENGINE.evaluate(facts(1,0,3,false,2,NOW)).stream().anyMatch(v->v.contains("场次数")));
    }
    @Test void saleWindowAndPerOrderAreIndependent(){
        assertTrue(ENGINE.evaluate(facts(1,0,0,false,2,NOW.minusNanos(1))).stream().anyMatch(v->v.contains("时间")));
        assertTrue(ENGINE.evaluate(facts(1,0,0,false,2,NOW.plusHours(1))).stream().anyMatch(v->v.contains("时间")));
        assertTrue(ENGINE.evaluate(facts(2,0,0,false,1,NOW)).stream().anyMatch(v->v.contains("每单")));
    }
    @Test void requestFactsNeverLeakBetweenSessions(){
        assertFalse(ENGINE.evaluate(facts(3,0,3,false,1,NOW)).isEmpty());assertTrue(ENGINE.evaluate(facts(1,0,0,false,2,NOW)).isEmpty());
    }
}
