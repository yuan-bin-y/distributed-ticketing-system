package com.byy.ticket.event.config;
import com.byy.ticket.event.service.EventPreparationWorkflow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
/** 持久化任务调度；实例间由数据库条件领取协调，不靠内存队列。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="ticket.event-preparation.enabled",havingValue="true",matchIfMissing=true)
public class EventPreparationScheduler {
    private final EventPreparationWorkflow workflow;
    public EventPreparationScheduler(EventPreparationWorkflow workflow){this.workflow=workflow;}
    @Scheduled(fixedDelayString="${ticket.event-preparation.fixed-delay:2000}")
    public void recover(){workflow.recoverDue();}
}
