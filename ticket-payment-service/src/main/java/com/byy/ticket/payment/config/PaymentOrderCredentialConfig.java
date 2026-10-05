package com.byy.ticket.payment.config;

import com.byy.ticket.security.service.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** 两个方向的独立服务凭证：发送付款通知，接收Order创建/查询/冲正。 */
@Configuration
@EnableConfigurationProperties({PaymentOrderCredentialProperties.class,OrderPaymentCredentialProperties.class})
public class PaymentOrderCredentialConfig {
    /** 加载与Order接收端相同的凭证，不能缺失时发送匿名内部请求。 */
    @Bean
    public PaymentOrderCredential paymentOrderCredential(PaymentOrderCredentialProperties properties)throws Exception{return new PaymentOrderCredential(properties);}
    /** 内部支付入口只接受Order独立凭证，不接受用户JWT或付款通知凭证。 */
    @Bean
    public OrderPaymentCredential orderPaymentCredential(OrderPaymentCredentialProperties properties)throws Exception{return new OrderPaymentCredential(properties);}
}
