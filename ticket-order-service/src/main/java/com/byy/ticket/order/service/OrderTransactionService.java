package com.byy.ticket.order.service;

import com.byy.ticket.order.client.dto.TicketPurchaseRuleResponse;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.mapper.OrderMapper;
import com.byy.ticket.order.mapper.OrderItemMapper;
import com.byy.ticket.order.model.TicketOrder;
import com.byy.ticket.order.model.OrderItem;
import com.byy.ticket.order.model.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** 订单本地事务边界；单独的 Spring Bean 保证调用经事务代理，内部不发送网络请求。 */
@Service
public class OrderTransactionService {
    private final OrderMapper orders;
    private final OrderItemMapper items;

    /** 注入属于订单库的两个 Mapper。 */
    public OrderTransactionService(OrderMapper orders, OrderItemMapper items) {
        this.orders = orders;
        this.items = items;
    }

    /** 原子保存主表和价格快照；唯一键竞争由调用方在本事务回滚后读取获胜订单。 */
    @Transactional
    public TicketOrder create(Long userId, OrderCreateDTO request, TicketPurchaseRuleResponse rule,
                              BigDecimal amount, LocalDateTime now, LocalDateTime expiresAt) {
        TicketOrder order = new TicketOrder();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setIdempotencyKey(request.idempotencyKey());
        order.setTotalAmount(amount);
        order.setStatus(OrderStatus.STOCK_PENDING.name());
        order.setExpiresAt(expiresAt);
        order.setNextAttemptAt(now);
        order.setAttemptCount(0);
        orders.insert(order);
        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setEventId(rule.eventId());
        item.setSessionId(rule.sessionId());
        item.setTicketTierId(rule.ticketTierId());
        item.setTicketTierName(rule.ticketTierName());
        item.setUnitPrice(rule.price());
        item.setQuantity(request.quantity());
        item.setSubtotalAmount(amount);
        items.insert(item);
        return order;
    }
}
