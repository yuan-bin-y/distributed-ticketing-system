package com.byy.ticket.order.service.impl;

import com.byy.ticket.order.client.EventCallGuard;
import com.byy.ticket.common.trace.PerformanceSpan;
import com.byy.ticket.order.client.InventoryClient;
import com.byy.ticket.order.client.PaymentClient;
import com.byy.ticket.order.client.dto.PaymentCreateRequest;
import com.byy.ticket.order.client.dto.StockReservationRequest;
import com.byy.ticket.order.client.exception.EventServiceCallException;
import com.byy.ticket.order.dto.order.OrderPreviewDTO;
import com.byy.ticket.order.dto.order.OrderStockReserveDTO;
import com.byy.ticket.order.service.OrderService;
import com.byy.ticket.order.vo.order.OrderPreviewVO;
import com.byy.ticket.order.vo.order.OrderStockReservationVO;
import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.order.dto.order.OrderCreateDTO;
import com.byy.ticket.order.vo.order.OrderDetailVO;
import com.byy.ticket.order.vo.order.OrderPaymentVO;
import com.byy.ticket.order.config.OrderWorkflowProperties;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.mapper.OrderMapper;
import com.byy.ticket.order.mapper.OrderItemMapper;
import com.byy.ticket.order.model.TicketOrder;
import com.byy.ticket.order.model.OrderStatus;
import com.byy.ticket.order.model.OrderItem;
import com.byy.ticket.order.service.OrderTransactionService;
import com.byy.ticket.order.service.OrderStockWorkflow;
import org.springframework.dao.DuplicateKeyException;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 订单业务编排：EventCallGuard保护规则查询，InventoryClient调用库存，PaymentClient创建支付单。
 * Client 发出 HTTP 请求；目标服务自己的 Controller、Service 和 Mapper 完成对应业务。
 */
@Service
public class OrderServiceImpl implements OrderService {
    private final EventCallGuard eventCallGuard;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final Clock clock;
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final OrderTransactionService transactions;
    private final OrderStockWorkflow workflow;
    private final OrderWorkflowProperties properties;

    /**
     * 注入远程 Client、本服务的订单 Mapper、短事务与恢复组件；不访问其他服务的数据库。
     */
    public OrderServiceImpl(EventCallGuard eventCallGuard, InventoryClient inventoryClient, Clock clock,
                            OrderMapper orders, OrderItemMapper items, OrderTransactionService transactions,
                            OrderStockWorkflow workflow, OrderWorkflowProperties properties, PaymentClient paymentClient) {
        this.eventCallGuard = eventCallGuard;
        this.inventoryClient = inventoryClient;
        this.clock = clock;
        this.orders = orders;
        this.items = items;
        this.transactions = transactions;
        this.workflow = workflow;
        this.properties = properties;
        this.paymentClient = paymentClient;
    }

    /**
     * 校验购买幂等键，查询规则并落库，再在本地事务之外调用库存。
     * 唯一键竞争发生时，事务 Bean 已回滚，才能读取并返回获胜请求的订单。
     */
    @Override
    public OrderDetailVO create(Long userId, OrderCreateDTO request) {
        try(var span=PerformanceSpan.open("order.create")){
            return PerformanceSpan.measure("business",()->createMeasured(userId,request));
        }
    }
    private OrderDetailVO createMeasured(Long userId, OrderCreateDTO request) {
        validateCreate(userId, request);
        TicketOrder existing = PerformanceSpan.measure("idempotency.lookup",()->orders.selectByRequest(userId, request.idempotencyKey()));
        if (existing != null) { return repeat(existing, request); }
        var rule = PerformanceSpan.measure("event.http",()->eventCallGuard.getPurchaseRule(request.ticketTierId()));
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        if (now.isBefore(rule.saleStartTime()) || !now.isBefore(rule.saleEndTime())) {
            throw new IllegalArgumentException("当前不在开售时间内");
        }
        if (request.quantity() > rule.purchaseLimit()) { throw new IllegalArgumentException("本次数量超过限购"); }
        if (rule.price().scale() > 2 || rule.price().compareTo(new BigDecimal("9999999999.99")) > 0
                || rule.ticketTierName().length() > 128) {
            throw new EventServiceCallException(
                    EventServiceCallException.Reason.INVALID_RESPONSE,
                    "活动规则不能保存为订单快照");
        }
        BigDecimal amount = rule.price().multiply(BigDecimal.valueOf(request.quantity()));
        if (amount.compareTo(new BigDecimal("9999999999999999.99")) > 0) {
            throw new IllegalArgumentException("订单金额超出可保存范围");
        }
        TicketOrder order;
        try {
            order = PerformanceSpan.measure("local.transaction",()->transactions.create(userId, request, rule, amount, now,
                    now.plus(properties.paymentWindow()).truncatedTo(ChronoUnit.MILLIS)));
        } catch (DuplicateKeyException exception) {
            existing = PerformanceSpan.measure("idempotency.lookup",()->orders.selectByRequest(userId, request.idempotencyKey()));
            if (existing == null) { throw exception; }
            return repeat(existing, request);
        }
        Long orderId=order.getId();
        PerformanceSpan.measure("inventory.workflow",()->{workflow.advance(orderId);return null;});
        return PerformanceSpan.measure("detail.query",()->detail(orders.selectById(orderId)));
    }

    /** 归属查询：不存在和不属于当前用户都返回 404，不泄漏其他用户订单。 */
    @Override
    public OrderDetailVO getOrder(Long userId, String orderNo) {
        if (userId == null || userId < 1 || orderNo == null || !orderNo.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("用户或订单编号不正确");
        }
        TicketOrder order = orders.selectOwned(userId, orderNo);
        if (order == null) { throw new ResourceNotFoundException("订单不存在"); }
        return detail(order);
    }

    /**
     * 发起支付只读取已保存的订单快照，HTTP调用不占用数据库事务。
     * 响应返回后再检查归属、状态与期限；若已关闭则不把支付单当作仍可付款返回。
     * 创建失败或响应丢失不改订单，重试仍使用同一订单编号、金额和期限。
     */
    @Override
    public OrderPaymentVO createPayment(Long userId, String orderNo) {
        if (userId == null || userId < 1 || orderNo == null || !orderNo.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("用户或订单编号不正确");
        }
        TicketOrder order = orders.selectOwned(userId, orderNo);
        if (order == null) { throw new ResourceNotFoundException("订单不存在"); }
        requirePayable(order);
        var payment = paymentClient.createPayment(new PaymentCreateRequest(order.getOrderNo(), order.getUserId(),
                order.getTotalAmount(), order.getExpiresAt()));
        TicketOrder current = orders.selectOwned(userId, orderNo);
        if (current == null) { throw new ResourceNotFoundException("订单不存在"); }
        requirePayable(current);
        return new OrderPaymentVO(payment.orderNo(), payment.paymentNo(), payment.amount(), payment.status(),
                payment.expiresAt(), payment.paidAt(), payment.reversalNo(), payment.reversalStatus());
    }

    /** 发起支付要求已确认库存预留、订单待支付且当前时间严格早于到期时间。 */
    private void requirePayable(TicketOrder order) {
        if (!OrderStatus.PENDING_PAYMENT.name().equals(order.getStatus())) {
            throw new OrderConflictException("当前订单状态不能发起支付");
        }
        if (order.getExpiresAt() == null || !order.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw new OrderConflictException("订单已到期，不能发起支付");
        }
        if (order.getReservationId() == null || !order.getReservationId().matches("[0-9a-f]{32}")) {
            throw new OrderConflictException("订单缺少有效库存预留，不能发起支付");
        }
    }

    /** 重复购买只核对快照并返回原订单；后台任务负责恢复，不突破持久化退避。 */
    private OrderDetailVO repeat(TicketOrder order, OrderCreateDTO request) {
        OrderItem item = items.selectByOrderId(order.getId());
        if (item == null) { throw new IllegalStateException("订单购买快照缺失"); }
        if (!item.getTicketTierId().equals(request.ticketTierId()) || !item.getQuantity().equals(request.quantity())) {
            throw new OrderConflictException("同一购买幂等键必须使用相同票档和数量");
        }
        return detail(order);
    }

    /** 把数据库主表和名称、价格快照组装为响应；不读取活动最新价格。 */
    private OrderDetailVO detail(TicketOrder order) {
        if (order == null) { throw new IllegalStateException("订单记录缺失"); }
        OrderItem item = items.selectByOrderId(order.getId());
        if (item == null) { throw new IllegalStateException("订单购买快照缺失"); }
        return new OrderDetailVO(order.getOrderNo(), order.getStatus(), item.getEventId(), item.getSessionId(),
                item.getTicketTierId(), item.getTicketTierName(), item.getUnitPrice(), item.getQuantity(),
                order.getTotalAmount(), order.getExpiresAt(), order.getReservationId(), order.getCreatedAt());
    }

    /** Service 入口也校验参数，避免内部调用绕过 Controller 的 Valid。 */
    private void validateCreate(Long userId, OrderCreateDTO request) {
        if (userId == null || userId < 1 || request == null || request.ticketTierId() == null
                || request.ticketTierId() < 1 || request.quantity() == null || request.quantity() < 1
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("下单参数不正确");
        }
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
     * 先校验参数，再经过 EventCallGuard 调用 EventClient 读取活动规则。
     * 检查当前时间在开售时间内、本次购买数量不超过限购，并按单价乘数量计算总价。
     * 只生成预览 VO，不创建订单、不预留库存，也不统计用户历史购买次数。
     */
    @Override
    public OrderPreviewVO preview(OrderPreviewDTO request) {
        if (request == null || request.ticketTierId() == null || request.ticketTierId() < 1
                || request.quantity() == null || request.quantity() < 1) {
            throw new IllegalArgumentException("票档ID和购买数量必须大于零");
        }
        var rule = PerformanceSpan.measure("event.http",()->eventCallGuard.getPurchaseRule(request.ticketTierId()));
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
