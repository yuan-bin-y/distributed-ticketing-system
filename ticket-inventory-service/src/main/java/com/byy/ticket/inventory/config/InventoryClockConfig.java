package com.byy.ticket.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneOffset;

/**
 * 提供库存服务使用的时钟，统一使用 UTC+8 判断新预留的到期时间。
 */
@Configuration
public class InventoryClockConfig {
    /**
     * 创建可注入的 Clock，业务代码通过它读取当前时间，便于验证时间相关行为。
     */
    @Bean
    public Clock inventoryClock() {
        return Clock.system(ZoneOffset.ofHours(8));
    }
}
