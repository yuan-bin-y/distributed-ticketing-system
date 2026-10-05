package com.byy.ticket.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Auth 独立入口：启动 HTTP 服务、连接 MySQL/Redis，执行 Flyway 建表。 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.byy.ticket.auth.mapper")
public class TicketAuthApplication {
    /** 启动认证服务。 */
    public static void main(String[] args) { SpringApplication.run(TicketAuthApplication.class, args); }
}
