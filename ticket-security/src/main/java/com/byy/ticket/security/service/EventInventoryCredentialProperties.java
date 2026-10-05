package com.byy.ticket.security.service;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
/** Event初始化Inventory的独立凭证，不能复用支付凭证。 */
@ConfigurationProperties("ticket.inventory-init-auth")
public record EventInventoryCredentialProperties(String token, Path credentialPath) { }
