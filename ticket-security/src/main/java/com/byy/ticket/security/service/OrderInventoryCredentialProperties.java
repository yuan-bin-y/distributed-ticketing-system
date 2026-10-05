package com.byy.ticket.security.service;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Order调用Inventory使用的独立凭证配置；发送端和接收端使用同一份配置。 */
@ConfigurationProperties("ticket.inventory-service-auth")
public record OrderInventoryCredentialProperties(String orderInventoryToken, Path credentialPath) { }
