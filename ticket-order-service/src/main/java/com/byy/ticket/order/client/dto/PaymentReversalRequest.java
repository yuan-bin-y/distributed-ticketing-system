package com.byy.ticket.order.client.dto;

/** 冲正重试使用订单表里保存的原付款编号与首次原因，不接受用户自报金额。 */
public record PaymentReversalRequest(String paymentNo, String reason) { }
