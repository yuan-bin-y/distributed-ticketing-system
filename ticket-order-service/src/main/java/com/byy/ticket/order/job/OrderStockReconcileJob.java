package com.byy.ticket.order.job;

import com.byy.ticket.order.config.OrderWorkflowProperties;
import com.byy.ticket.order.service.OrderStockWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 调度库存预留、未付款关闭、付款确认、冲正及出票恢复；未来MQ触发也复用同一推进逻辑。 */
@Component
public class OrderStockReconcileJob {
    private static final Logger log = LoggerFactory.getLogger(OrderStockReconcileJob.class);
    private final OrderStockWorkflow workflow;
    private final OrderWorkflowProperties properties;

    /** 注入恢复流程与开关。 */
    public OrderStockReconcileJob(OrderStockWorkflow workflow, OrderWorkflowProperties properties) {
        this.workflow = workflow;
        this.properties = properties;
    }

    /** 每轮完成后再等待固定间隔；数据库异常不停止后续调度。 */
    @Scheduled(fixedDelayString = "${ticket.order.recovery-delay-ms:5000}")
    public void reconcile() {
        if (!properties.recoveryEnabled()) { return; }
        try { workflow.recoverDue(); }
        catch (RuntimeException exception) { log.error("扫描订单恢复任务失败，下轮重试", exception); }
    }
}
