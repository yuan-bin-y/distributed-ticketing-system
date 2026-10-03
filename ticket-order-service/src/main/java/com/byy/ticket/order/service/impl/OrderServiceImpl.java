package com.byy.ticket.order.service.impl;

import com.byy.ticket.order.client.EventClient;
import com.byy.ticket.order.client.InventoryClient;
import com.byy.ticket.order.client.dto.StockReservationRequest;
import com.byy.ticket.order.dto.order.OrderPreviewDTO;
import com.byy.ticket.order.dto.order.OrderStockReserveDTO;
import com.byy.ticket.order.service.OrderService;
import com.byy.ticket.order.vo.order.OrderPreviewVO;
import com.byy.ticket.order.vo.order.OrderStockReservationVO;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 订单业务编排：通过 EventClient 查询活动规则，通过 InventoryClient 调用库存。
 * Client 发出 HTTP 请求；目标服务自己的 Controller、Service 和 Mapper 完成对应业务。
 */
@Service
public class OrderServiceImpl implements OrderService {
    private final EventClient eventClient;
    private final InventoryClient inventoryClient;
    private final Clock clock;

    /**
     * 注入活动 Client、库存 Client 和时钟，不注入其他微服务的 Service 或 Mapper。
     */
    public OrderServiceImpl(EventClient eventClient, InventoryClient inventoryClient, Clock clock) {
        this.eventClient = eventClient;
        this.inventoryClient = inventoryClient;
        this.clock = clock;
    }

    /**
     * 把订单侧 DTO 转成远程请求对象，调用 InventoryClient.reserve，再转换返回 VO。
     * 库存服务独立完成幂等与库存事务；这里当前没有订单持久化、活动规则或身份校验。
     */
    @Override
    public OrderStockReservationVO reserveStock(OrderStockReserveDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("库存预留参数不能为空");
        }
        var reservation = inventoryClient.reserve(new StockReservationRequest(request.orderId(), request.sessionId(),
                request.ticketTierId(), request.quantity(), request.expiresAt()));
        return new OrderStockReservationVO(reservation.reservationId(), reservation.orderId(), reservation.sessionId(),
                reservation.ticketTierId(), reservation.quantity(), reservation.expiresAt(), reservation.status());
    }

    /**
     * 先校验参数，再调用 EventClient.getPurchaseRule 读取活动规则。
     * 检查当前时间在开售时间内、本次购买数量不超过限购，并按单价乘数量计算总价。
     * 只生成预览 VO，不创建订单、不预留库存，也不统计用户历史购买次数。
     */
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
