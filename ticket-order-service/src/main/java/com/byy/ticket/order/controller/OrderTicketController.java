package com.byy.ticket.order.controller;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.service.OrderTicketService;
import com.byy.ticket.order.vo.order.OrderTicketsVO;
import com.byy.ticket.order.web.OrderIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 电子票查询入口，沿用网关/api/orders路由和订单当前用户身份规则。 */
@RestController
@RequestMapping("/api/orders")
public class OrderTicketController {
    private final OrderTicketService tickets;
    private final OrderIdentityResolver identity;

    /** 注入查询业务接口和现有用户身份解析器。 */
    public OrderTicketController(OrderTicketService tickets,OrderIdentityResolver identity) {
        this.tickets=tickets;this.identity=identity;
    }

    /** GET /api/orders/{orderNo}/tickets：返回本人订单状态及按序号排序的每张票。 */
    @GetMapping("/{orderNo}/tickets")
    public Result<OrderTicketsVO> getTickets(@PathVariable String orderNo,HttpServletRequest request) {
        return Result.success(tickets.getTickets(identity.requireUser(request),orderNo));
    }
}
