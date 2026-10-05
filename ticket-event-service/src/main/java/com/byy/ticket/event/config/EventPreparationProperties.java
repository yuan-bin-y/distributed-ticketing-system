package com.byy.ticket.event.config;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
/** 初始化调用超时独立配置；租约60秒覆盖有界调用，重试由持久化任务驱动。 */
@ConfigurationProperties("ticket.event-preparation")
public record EventPreparationProperties(String serviceId,Duration connectTimeout,Duration readTimeout){
    public EventPreparationProperties{
        serviceId=serviceId==null?"ticket-inventory-service":serviceId;
        connectTimeout=connectTimeout==null?Duration.ofSeconds(2):connectTimeout;
        readTimeout=readTimeout==null?Duration.ofSeconds(3):readTimeout;
        if(!serviceId.matches("[A-Za-z0-9][A-Za-z0-9-]*")||connectTimeout.isNegative()||connectTimeout.isZero()||readTimeout.isNegative()||readTimeout.isZero()
                ||connectTimeout.compareTo(Duration.ofSeconds(15))>0||readTimeout.compareTo(Duration.ofSeconds(15))>0)throw new IllegalArgumentException("库存初始化服务名或超时配置无效");
    }
}
