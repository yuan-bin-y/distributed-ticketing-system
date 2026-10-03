package com.byy.ticket.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关启动入口：启动 WebFlux 网关，通过配置中的路由把外部请求转发到对应业务服务。
 */
@SpringBootApplication
public class TicketGatewayApplication {

    /**
     * 启动网关，加载路由、服务发现和负载均衡配置。网关不连接业务数据库。
     */
    public static void main(String[] args) {
        SpringApplication.run(TicketGatewayApplication.class, args);
    }
}
