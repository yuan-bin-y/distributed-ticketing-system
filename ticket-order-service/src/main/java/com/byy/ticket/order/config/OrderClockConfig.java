package com.byy.ticket.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneOffset;

/** 购票规则中的本地时间统一按东八区解释。 */
@Configuration
public class OrderClockConfig {
    @Bean
    public Clock orderClock() {
        return Clock.system(ZoneOffset.ofHours(8));
    }
}
