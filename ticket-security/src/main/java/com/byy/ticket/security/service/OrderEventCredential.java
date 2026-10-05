package com.byy.ticket.security.service;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Order到Event的256位随机服务凭证；仅授予内部购票规则查询权限。 */
public final class OrderEventCredential {
    public static final String HEADER="X-Order-Event-Credential";
    private final String value;
    /** 环境配置优先，否则读取持久化文件；缺失时启动失败，不降级成匿名访问。 */
    public OrderEventCredential(OrderEventCredentialProperties properties)throws java.io.IOException {
        String configured=properties.orderEventToken();
        if(configured==null||configured.isBlank()){
            if(properties.credentialPath()==null)throw new IllegalArgumentException("未配置订单调用活动的服务凭证");
            configured=Files.readString(properties.credentialPath()).strip();
        }
        if(!configured.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("订单活动服务凭证必须为64位小写十六进制随机值");
        value=configured;
    }
    /** 发送端添加请求头；禁止输出到日志或用户响应。 */
    public String value(){return value;}
    /** 接收端常量时间比较；付款通知凭证不能复用到此入口。 */
    public boolean matches(String candidate){return candidate!=null&&MessageDigest.isEqual(value.getBytes(StandardCharsets.US_ASCII),candidate.getBytes(StandardCharsets.US_ASCII));}
}
