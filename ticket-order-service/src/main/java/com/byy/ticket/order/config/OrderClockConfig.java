package com.byy.ticket.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneOffset;

/**
 * 提供订单服务使用的时钟，统一使用 UTC+8 判断开售和停售时间。
 */
@Configuration
public class OrderClockConfig {
    /**
     * 创建可注入的 Clock，订单预览通过它读取当前时间。
     */
    @Bean
    public Clock orderClock() {
        return Clock.system(ZoneOffset.ofHours(8));
    }
}
