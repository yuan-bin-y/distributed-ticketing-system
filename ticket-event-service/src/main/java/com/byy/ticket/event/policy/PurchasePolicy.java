package com.byy.ticket.event.policy;

/** 已发布版本不可修改；版本零兼容历史活动，保留原场次限购规则。 */
public record PurchasePolicy(Long eventId,Long version,Integer maxSessions,Integer maxPerOrder,String status,String note){}
