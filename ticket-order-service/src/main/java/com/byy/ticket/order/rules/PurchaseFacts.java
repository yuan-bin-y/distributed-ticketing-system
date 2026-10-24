package com.byy.ticket.order.rules;

import java.time.LocalDateTime;
import lombok.Getter;

/** 规则只读取已经收集好的事实，不在规则执行中调用数据库或其他服务。 */
@Getter
public final class PurchaseFacts {
    private final int quantity,occupiedTickets,occupiedSessions,purchaseLimit,maxSessions,maxPerOrder;
    private final boolean currentSessionOccupied,saleOpen;
    public PurchaseFacts(int quantity,int occupiedTickets,int occupiedSessions,boolean currentSessionOccupied,
            int purchaseLimit,int maxSessions,int maxPerOrder,LocalDateTime now,LocalDateTime start,LocalDateTime end){
        this.quantity=quantity;this.occupiedTickets=occupiedTickets;this.occupiedSessions=occupiedSessions;this.currentSessionOccupied=currentSessionOccupied;
        this.purchaseLimit=purchaseLimit;this.maxSessions=maxSessions;this.maxPerOrder=maxPerOrder;this.saleOpen=!now.isBefore(start)&&now.isBefore(end);
    }
}
