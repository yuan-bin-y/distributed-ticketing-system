package com.byy.ticket.order.service;

import com.byy.ticket.order.mapper.OrderMapper;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

/** 付款事务提交后尽快确认/出票；队列满或重启时交给原持久化扫描，不改变消息ACK边界。 */
@Component
public class PaymentFulfillmentTrigger {
    private final OrderStockWorkflow workflow;
    private final OrderMapper orders;
    private final TaskExecutor executor;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    public PaymentFulfillmentTrigger(OrderStockWorkflow workflow, OrderMapper orders,
            @Qualifier("paymentFulfillmentExecutor") TaskExecutor executor) {
        this.workflow = workflow; this.orders = orders; this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(PaymentFulfillmentRequested event) {
        if (!pending.add(event.orderId())) return;
        try {
            executor.execute(() -> {
                try {
                    for (int step = 0; step < 3; step++) {
                        var order = orders.selectById(event.orderId());
                        if (order == null || !Set.of("PAYMENT_CONFIRMING", "PAID", "REVERSAL_PENDING").contains(order.getStatus())) break;
                        workflow.advance(event.orderId());
                    }
                } catch (RuntimeException failure) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("付款即时履约未完成，交给后台恢复 orderId={}", event.orderId());
                } finally { pending.remove(event.orderId()); }
            });
        } catch (TaskRejectedException full) {
            pending.remove(event.orderId());
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("付款即时履约队列已满，交给后台恢复");
        }
    }
}
