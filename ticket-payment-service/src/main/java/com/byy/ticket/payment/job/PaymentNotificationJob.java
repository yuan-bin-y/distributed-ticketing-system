package com.byy.ticket.payment.job;

import com.byy.ticket.payment.config.PaymentNotificationProperties;
import com.byy.ticket.payment.service.PaymentNotificationWorkflow;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 固定延迟扫描成功但未送达的通知；每批结束后再等待，重试进度保存在数据库。 */
@Component
public class PaymentNotificationJob {
    private final PaymentNotificationWorkflow workflow;
    private final PaymentNotificationProperties properties;
    @org.springframework.beans.factory.annotation.Value("${ticket.mq.enabled:false}")
    private boolean mqEnabled;

    /** 注入可独立验证的流程与任务开关。 */
    public PaymentNotificationJob(PaymentNotificationWorkflow workflow, PaymentNotificationProperties properties) {
        this.workflow = workflow; this.properties = properties;
    }

    /** 显式关闭任务时保留 PENDING，之后重新开启可以恢复。 */
    @Scheduled(fixedDelayString="${ticket.payment.notification.fixed-delay:5000}")
    public void reconcile() { if (properties.enabled() && !mqEnabled) { workflow.recoverDue(); } }
}
