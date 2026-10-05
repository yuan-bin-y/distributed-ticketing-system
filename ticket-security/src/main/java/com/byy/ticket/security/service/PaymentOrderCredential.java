package com.byy.ticket.security.service;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 仅用于Payment -> Order通知的256位随机开发服务凭证，不是用户Token。 */
public final class PaymentOrderCredential {
    public static final String HEADER="X-Payment-Order-Credential";
    private final String value;
    /** 启动时加载持久化凭证；禁止缺失时放行或使用内置固定密码。 */
    public PaymentOrderCredential(PaymentOrderCredentialProperties properties) throws java.io.IOException {
        String configured=properties.paymentOrderToken();
        if (configured==null||configured.isBlank()) {
            if(properties.credentialPath()==null)throw new IllegalArgumentException("未配置支付通知服务凭证");
            configured=Files.readString(properties.credentialPath()).strip();
        }
        if(!configured.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("支付通知服务凭证必须为64位小写十六进制随机值");
        value=configured;
    }
    /** 发送端添加凭证头；不要写入日志或用户响应。 */
    public String value() { return value; }
    /** 接收端使用常量时间比较，不接受用户JWT冒充服务凭证。 */
    public boolean matches(String candidate) {
        return candidate!=null&&MessageDigest.isEqual(value.getBytes(StandardCharsets.US_ASCII),candidate.getBytes(StandardCharsets.US_ASCII));
    }
}
