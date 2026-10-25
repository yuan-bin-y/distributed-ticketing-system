package com.byy.ticket.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.byy.ticket.order.mapper.OrderMapper;
import com.byy.ticket.order.model.TicketOrder;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

import java.util.*;

class PaymentFulfillmentTriggerTest {
    @Configuration
    @EnableTransactionManagement
    @Import(PaymentFulfillmentTrigger.class)
    static class Config {
        @Bean
        List<Runnable> jobs() {
            return new ArrayList<>();
        }

        @Bean("paymentFulfillmentExecutor")
        TaskExecutor executor(List<Runnable> jobs) {
            return jobs::add;
        }

        @Bean
        OrderStockWorkflow workflow() {
            return mock(OrderStockWorkflow.class);
        }

        @Bean
        OrderMapper orders() {
            return mock(OrderMapper.class);
        }

        @Bean
        PlatformTransactionManager transactions() {
            return new AbstractPlatformTransactionManager() {
                protected Object doGetTransaction() {
                    return new Object();
                }

                protected void doBegin(Object tx, TransactionDefinition definition) {}

                protected void doCommit(DefaultTransactionStatus status) {}

                protected void doRollback(DefaultTransactionStatus status) {}
            };
        }
    }

    @Test
    void onlyCommittedReceiptQueuesWorkAndDuplicateTriggersCoalesce() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            @SuppressWarnings("unchecked")
            List<Runnable> jobs = context.getBean(List.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.execute(
                    status -> {
                        context.publishEvent(new PaymentFulfillmentRequested(1));
                        assertTrue(jobs.isEmpty());
                        status.setRollbackOnly();
                        return null;
                    });
            assertTrue(jobs.isEmpty());
            tx.execute(
                    status -> {
                        context.publishEvent(new PaymentFulfillmentRequested(1));
                        assertTrue(jobs.isEmpty());
                        return null;
                    });
            tx.execute(
                    status -> {
                        context.publishEvent(new PaymentFulfillmentRequested(1));
                        return null;
                    });
            assertEquals(1, jobs.size());
            var order = new TicketOrder();
            order.setStatus("PAYMENT_CONFIRMING");
            when(context.getBean(OrderMapper.class).selectById(1L)).thenReturn(order);
            jobs.remove(0).run();
            verify(context.getBean(OrderStockWorkflow.class), times(3)).advance(1L);
            tx.execute(
                    status -> {
                        context.publishEvent(new PaymentFulfillmentRequested(1));
                        return null;
                    });
            assertEquals(1, jobs.size());
        }
    }
}
