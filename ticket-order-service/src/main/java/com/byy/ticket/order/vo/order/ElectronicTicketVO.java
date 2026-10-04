package com.byy.ticket.order.vo.order;

import java.time.LocalDateTime;

/** 当前用户获得的单张电子票，活动信息取下单时的快照，不查询其他服务数据库。 */
public record ElectronicTicketVO(String ticketNo,Integer ticketIndex,String status,Long eventId,
        Long sessionId,Long ticketTierId,String ticketTierName,LocalDateTime createdAt) { }
