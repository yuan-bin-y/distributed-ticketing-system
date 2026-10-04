package com.byy.ticket.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 支付服务独立入口：加载 Nacos、支付数据库及 Flyway，提供模拟支付和冲正能力。 */
@SpringBootApplication
public class TicketPaymentApplication {
    /** 启动本服务；本阶段不访问订单或库存数据库，也不发送支付结果通知。 */
    public static void main(String[] args) {
        SpringApplication.run(TicketPaymentApplication.class, args);
    }
}
