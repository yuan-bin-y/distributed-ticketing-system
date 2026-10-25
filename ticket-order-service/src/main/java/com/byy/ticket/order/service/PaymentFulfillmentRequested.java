package com.byy.ticket.order.service;

/** 仅用于进程内加速；可靠恢复依据仍是已提交订单状态。 */
public record PaymentFulfillmentRequested(long orderId) { }
