package com.byy.ticket.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模拟支付与临时身份开关，默认均关闭。
 * @param simulationEnabled 显式开启后才允许模拟付款成功。
 * @param devIdentityEnabled 显式开启后才允许以开发请求头提供用户身份。
 */
@ConfigurationProperties("ticket.payment")
public record PaymentProperties(boolean simulationEnabled, boolean devIdentityEnabled) { }
