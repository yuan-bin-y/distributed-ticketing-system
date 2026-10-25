package com.byy.ticket.inventory.admission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 活跃账本与历史幂等凭据分离；归档在库存行锁保护下以小事务执行。 */
@Component
public class AdmissionArchiveJob {
    private final StockAdmissionService service;
    private final int retentionDays;
    public AdmissionArchiveJob(StockAdmissionService service,
            @Value("${ticket.stock-admission.retention-days:7}") int retentionDays) {
        if(retentionDays<1)throw new IllegalArgumentException("预扣账本保留天数至少为1");
        this.service=service;this.retentionDays=retentionDays;
    }
    @Scheduled(fixedDelayString="${ticket.stock-admission.archive-ms:60000}")
    public void archive(){service.archive(retentionDays);}
}
