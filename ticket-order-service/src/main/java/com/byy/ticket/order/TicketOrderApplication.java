package com.byy.ticket.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 订单服务启动入口：提供订单预览和内部库存预留调用，当前尚未创建订单表。
 */
@SpringBootApplication
public class TicketOrderApplication {
    /**
     * 启动 Spring Boot 应用，加载配置并创建 Spring 管理的组件。
     * Nacos 注册、数据库连接和 Flyway 迁移由相应依赖与配置在启动过程中触发。
     */
    public static void main(String[] args) {
        SpringApplication.run(TicketOrderApplication.class, args);
    }
}
