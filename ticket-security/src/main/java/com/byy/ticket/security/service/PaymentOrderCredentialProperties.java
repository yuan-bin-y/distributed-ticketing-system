package com.byy.ticket.security.service;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Payment通知Order的独立凭证，环境变量优先，否则从本机持久化文件读取。 */
@ConfigurationProperties("ticket.service-auth")
public record PaymentOrderCredentialProperties(String paymentOrderToken, Path credentialPath) { }
