package com.byy.ticket.payment.service;

import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.payment.mapper.OutboxEventMapper;
import com.byy.ticket.payment.model.*;
import com.byy.ticket.payment.mq.event.PaymentSucceededEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

/** 只在付款事务内保存事件，不连接RabbitMQ，不调用其他服务。 */
@Service
public class PaymentOutboxService {
    private final OutboxEventMapper events;
    private final ObjectMapper json;

    /** 注入本库Mapper与应用JSON转换器，保证载荷使用固定消息契约。 */
    public PaymentOutboxService(OutboxEventMapper events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    /** 必须加入已存在的付款事务；插入或序列化失败会使付款成功更新一起回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(Payment payment) {
        requireSuccess(payment);
        String eventId = UUID.randomUUID().toString().replace("-", "");
        var message = new PaymentSucceededEvent(eventId, PaymentSucceededEvent.TYPE,
                PaymentSucceededEvent.VERSION, payment.getOrderNo(), payment.getPaymentNo());
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setEventType(message.eventType());
        event.setAggregateId(payment.getPaymentNo());
        event.setPayload(json.writeValueAsString(message));
        event.setTraceId(TraceIdContext.getOrCreate());
        event.setStatus("PENDING");
        event.setAttemptCount(0);
        event.setNextAttemptAt(payment.getPaidAt());
        event.setCreatedAt(payment.getPaidAt());
        if (events.insert(event) != 1) {
            throw new IllegalStateException("支付成功事件保存失败");
        }
    }

    /** 重复付款只核对原事件，不改载荷、编号、尝试次数或发布状态。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireExisting(Payment payment) {
        requireSuccess(payment);
        OutboxEvent event = events.selectPaymentSucceeded(payment.getPaymentNo());
        if (event == null) { throw new IllegalStateException("成功支付缺少Outbox事件，请核对迁移或数据"); }
        var message = json.readValue(event.getPayload(), PaymentSucceededEvent.class);
        if (!event.getEventId().equals(message.eventId())
                || !PaymentSucceededEvent.TYPE.equals(message.eventType())
                || message.schemaVersion() != PaymentSucceededEvent.VERSION
                || !payment.getOrderNo().equals(message.orderNo())
                || !payment.getPaymentNo().equals(message.paymentNo())) {
            throw new IllegalStateException("支付成功事件与原付款事实不一致");
        }
    }

    /** 只允许在付款事实已经更新为SUCCESS且有首次付款时间后建立事件。 */
    private void requireSuccess(Payment payment) {
        if (payment == null || !PaymentStatus.SUCCESS.name().equals(payment.getStatus())
                || payment.getPaidAt() == null) {
            throw new IllegalStateException("仅成功付款可以保存支付成功事件");
        }
    }
}
