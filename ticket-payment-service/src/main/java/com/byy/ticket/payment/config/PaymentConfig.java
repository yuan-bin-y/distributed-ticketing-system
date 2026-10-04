package com.byy.ticket.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneOffset;

/** 注册支付配置与统一时钟，数据库及接口时间均按固定东八区解释。 */
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {
    /** 提供可替换时钟，让到期行为可以用固定时间验证。 */
    @Bean
    public Clock paymentClock() { return Clock.system(ZoneOffset.ofHours(8)); }
}
