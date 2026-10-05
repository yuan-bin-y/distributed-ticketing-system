package com.byy.ticket.security.service;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
/** 初始化接口仅授权Event服务；缺少凭证时启动失败。 */
public final class EventInventoryCredential {
    public static final String HEADER="X-Event-Inventory-Credential";
    private final String value;
    public EventInventoryCredential(EventInventoryCredentialProperties properties)throws java.io.IOException {
        String configured=properties.token();
        if(configured==null||configured.isBlank())configured=Files.readString(properties.credentialPath()).strip();
        if(!configured.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("库存初始化凭证必须为64位小写十六进制随机值");
        value=configured;
    }
    public String value(){return value;}
    public boolean matches(String candidate){return candidate!=null&&MessageDigest.isEqual(value.getBytes(StandardCharsets.US_ASCII),candidate.getBytes(StandardCharsets.US_ASCII));}
}
