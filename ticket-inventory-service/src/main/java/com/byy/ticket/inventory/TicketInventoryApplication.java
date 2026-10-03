package com.byy.ticket.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 库存服务启动入口：提供库存预留、确认售出、释放和查询接口，独立连接库存数据库。
 */
@SpringBootApplication
public class TicketInventoryApplication {
    /**
     * 启动 Spring Boot 应用，加载配置并创建 Spring 管理的组件。
     * Nacos 注册、数据库连接和 Flyway 迁移由相应依赖与配置在启动过程中触发。
     */
    public static void main(String[] args) {
        SpringApplication.run(TicketInventoryApplication.class, args);
    }
}
