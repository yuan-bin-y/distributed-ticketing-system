package com.byy.ticket.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 支付服务独立入口：加载 Nacos、支付数据库及 Flyway，提供模拟支付和冲正能力。 */
@SpringBootApplication
public class TicketPaymentApplication {
    /** 启动本服务；按配置通过HTTP或MQ通知订单，只访问本服务数据库。 */
    public static void main(String[] args) {
        SpringApplication.run(TicketPaymentApplication.class, args);
    }
}
