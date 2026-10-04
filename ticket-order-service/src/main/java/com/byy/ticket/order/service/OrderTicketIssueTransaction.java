package com.byy.ticket.order.service;

import com.byy.ticket.order.mapper.*;
import com.byy.ticket.order.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 独立事务Bean：全部电子票和COMPLETED状态一起提交；内部不发送网络请求。 */
@Service
public class OrderTicketIssueTransaction {
    private final OrderMapper orders;
    private final OrderItemMapper items;
    private final ElectronicTicketMapper tickets;

    /** 注入属于订单库的三个Mapper。 */
    public OrderTicketIssueTransaction(OrderMapper orders,OrderItemMapper items,ElectronicTicketMapper tickets) {
        this.orders=orders;this.items=items;this.tickets=tickets;
    }

    /** 锁定原订单并核对领取令牌；重复或过期领取者不能插票或覆盖新进度。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void issue(Long orderId,String token) {
        var order=orders.selectByIdForUpdate(orderId);
        if(order==null || !OrderStatus.PAID.name().equals(order.getStatus())
                || !Objects.equals(token,order.getLeaseToken()) || token==null) { return; }
        var item=items.selectByOrderId(orderId);
        if(item==null || item.getQuantity()==null || item.getQuantity()<1) {
            throw new IllegalArgumentException("出票缺少有效购买快照");
        }
        var existing=tickets.selectByItemForUpdate(item.getId());
        Set<Integer> indices=new HashSet<>();
        for(var ticket:existing) {
            if(ticket.getTicketIndex()<1 || ticket.getTicketIndex()>item.getQuantity()
                    || !"VALID".equals(ticket.getStatus()) || !indices.add(ticket.getTicketIndex())) {
                throw new IllegalArgumentException("已保存电子票与购买数量不一致，需核对");
            }
        }
        List<ElectronicTicket> batch=new ArrayList<>(100);
        for(int offset=0;offset<item.getQuantity();offset++) {
            int index=offset+1;
            if(indices.contains(index)) { continue; }
            var ticket=new ElectronicTicket();
            ticket.setTicketNo(UUID.randomUUID().toString().replace("-",""));
            ticket.setOrderItemId(item.getId());ticket.setTicketIndex(index);ticket.setStatus("VALID");
            batch.add(ticket);
            if(batch.size()==100) { insertBatch(batch);batch.clear(); }
        }
        if(!batch.isEmpty()) { insertBatch(batch); }
        if(orders.finishIssuing(orderId,token)!=1) {
            throw new IllegalStateException("出票完成状态保存失败");
        }
    }

    /** 检查批量写入数量；调用仍处于issue的同一事务中，失败连同前批一起回滚。 */
    private void insertBatch(List<ElectronicTicket> batch) {
        if(tickets.insertTickets(batch)!=batch.size()) { throw new IllegalStateException("电子票保存数量不一致"); }
    }
}
