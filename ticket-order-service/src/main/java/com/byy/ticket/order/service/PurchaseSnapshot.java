package com.byy.ticket.order.service;

import com.byy.ticket.order.client.dto.TicketPurchaseRuleResponse;

import java.math.BigDecimal;

/** 同步和异步入口共用价格快照边界；两者最终使用同一个建单事务和履约状态机。 */
public final class PurchaseSnapshot {
    private PurchaseSnapshot() {}

    public static BigDecimal amount(TicketPurchaseRuleResponse rule, int quantity) {
        if (quantity < 1
                || rule.price().signum() <= 0
                || rule.price().scale() > 2
                || rule.price().compareTo(new BigDecimal("9999999999.99")) > 0
                || rule.ticketTierName().isBlank()
                || rule.ticketTierName().length() > 128)
            throw new IllegalArgumentException("活动价格或票档名称不能保存为订单快照");
        BigDecimal amount = rule.price().multiply(BigDecimal.valueOf(quantity));
        if (amount.compareTo(new BigDecimal("9999999999999999.99")) > 0)
            throw new IllegalArgumentException("订单金额超出可保存范围");
        return amount;
    }
}
