package com.byy.ticket.order.service.impl;

import com.byy.ticket.order.client.EventClient;
import com.byy.ticket.order.dto.order.OrderPreviewDTO;
import com.byy.ticket.order.service.OrderService;
import com.byy.ticket.order.vo.order.OrderPreviewVO;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/** 通过远程规则完成购票预览校验。 */
@Service
public class OrderServiceImpl implements OrderService {
    private final EventClient eventClient;
    private final Clock clock;

    public OrderServiceImpl(EventClient eventClient, Clock clock) {
        this.eventClient = eventClient;
        this.clock = clock;
    }

    @Override
    public OrderPreviewVO preview(OrderPreviewDTO request) {
        if (request == null || request.ticketTierId() == null || request.ticketTierId() < 1
                || request.quantity() == null || request.quantity() < 1) {
            throw new IllegalArgumentException("票档ID和购买数量必须大于零");
        }
        var rule = eventClient.getPurchaseRule(request.ticketTierId());
        LocalDateTime now = LocalDateTime.now(clock);
        // 开售时间包含起点，停售时间不包含终点：[saleStartTime, saleEndTime)。
        if (now.isBefore(rule.saleStartTime())) {
            throw new IllegalArgumentException("尚未开售");
        }
        if (!now.isBefore(rule.saleEndTime())) {
            throw new IllegalArgumentException("已停止售票");
        }
        if (request.quantity() > rule.purchaseLimit()) {
            throw new IllegalArgumentException("本次购买数量超过场次限购数量");
        }
        BigDecimal totalAmount = rule.price().multiply(BigDecimal.valueOf(request.quantity()));
        return new OrderPreviewVO(rule.eventId(), rule.sessionId(), rule.ticketTierId(), rule.ticketTierName(),
                rule.price(), request.quantity(), totalAmount);
    }
}
