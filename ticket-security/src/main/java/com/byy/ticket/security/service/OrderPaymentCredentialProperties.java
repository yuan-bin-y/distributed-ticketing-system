package com.byy.ticket.security.service;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Order调用Payment使用的独立凭证配置，与Payment通知Order的凭证分离。 */
@ConfigurationProperties("ticket.payment-service-auth")
public record OrderPaymentCredentialProperties(String orderPaymentToken, Path credentialPath) { }
