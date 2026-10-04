package com.byy.ticket.payment.model;

/** 支付事实状态；SUCCESS 不表示订单已履约，也不因冲正改回 CREATED。 */
public enum PaymentStatus { CREATED, SUCCESS }
