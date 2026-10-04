package com.byy.ticket.payment.service;

import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.payment.client.OrderClient;
import com.byy.ticket.payment.config.PaymentNotificationProperties;
import com.byy.ticket.payment.mapper.PaymentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 数据库驱动的至少一次通知：短 SQL 领取、事务外发送、带令牌保存完成或重试时间。 */
@Service
public class PaymentNotificationWorkflow {
    private static final Logger log = LoggerFactory.getLogger(PaymentNotificationWorkflow.class);
    private final PaymentMapper payments;
    private final OrderClient orders;
    private final PaymentNotificationProperties properties;
    private final Clock clock;

    /** 注入本库持久化、远程通知客户端、退避配置与时钟。 */
    public PaymentNotificationWorkflow(PaymentMapper payments, OrderClient orders,
            PaymentNotificationProperties properties, Clock clock) {
        this.payments = payments; this.orders = orders; this.properties = properties; this.clock = clock;
    }

    /** 每条通知独立处理；一条失败不阻塞整批，线程复用时清理 trace。 */
    public void recoverDue() {
        for (Long id : payments.selectNotifyDue(properties.batchSize())) {
            TraceIdContext.setOrCreate(null);
            try { advance(id); }
            catch (RuntimeException failure) { log.error("通知进度更新失败，租约到期后恢复，paymentId={}", id, failure); }
            finally { TraceIdContext.clear(); }
        }
    }

    /** 只领取到期通知；保存失败或进程崩溃时，原 PENDING 记录仍能在租约到期后恢复。 */
    public void advance(Long id) {
        String token = UUID.randomUUID().toString().replace("-", "");
        if (payments.claimNotification(id, token, properties.leaseDuration().toNanos() / 1000) != 1) { return; }
        var payment = payments.selectById(id);
        if (payment == null || !token.equals(payment.getNotifyLeaseToken())) { return; }
        try {
            orders.notifySuccess(payment.getOrderNo(), payment.getPaymentNo());
        } catch (RuntimeException failure) {
            long factor = 1L << Math.min(Math.max(payment.getNotifyAttemptCount() - 1, 0), 10);
            long delay = Math.min(properties.retryMax().toMillis(), properties.retryBase().toMillis() * factor);
            // 不保存上游报文或可能含凭证的 URL，只保存异常类型。
            payments.finishNotification(id, token, "PENDING", now().plus(delay, ChronoUnit.MILLIS),
                    "订单通知失败：" + failure.getClass().getSimpleName());
            log.warn("支付通知待重试，paymentNo={}, attempt={}", payment.getPaymentNo(), payment.getNotifyAttemptCount());
            return;
        }
        payments.finishNotification(id, token, "DELIVERED", now(), null);
    }

    /** 东八区、毫秒精度，与通知数据库保持一致。 */
    private LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS); }
}
