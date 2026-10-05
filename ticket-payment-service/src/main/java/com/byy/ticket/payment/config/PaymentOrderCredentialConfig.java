package com.byy.ticket.payment.config;

import com.byy.ticket.security.service.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** 本阶段仅让Payment发送通知服务凭证，不改变Payment用户接口认证方式。 */
@Configuration
@EnableConfigurationProperties(PaymentOrderCredentialProperties.class)
public class PaymentOrderCredentialConfig {
    /** 加载与Order接收端相同的凭证，不能缺失时发送匿名内部请求。 */
    @Bean
    public PaymentOrderCredential paymentOrderCredential(PaymentOrderCredentialProperties properties)throws Exception{return new PaymentOrderCredential(properties);}
}
