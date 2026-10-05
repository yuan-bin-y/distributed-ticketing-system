package com.byy.ticket.order.config;

import com.byy.ticket.security.config.TicketSecurityProperties;
import com.byy.ticket.security.jwt.*;
import com.byy.ticket.security.session.AuthSessionReader;
import com.byy.ticket.security.service.*;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestTemplate;

/** Order使用同步JwtDecoder；公钥验签、声明和Redis会话规则与网关一致。 */
@Configuration
@EnableConfigurationProperties({TicketSecurityProperties.class,PaymentOrderCredentialProperties.class})
public class OrderJwtConfig {
    /** 当前Servlet模型使用同步Redis读取，不访问Auth用户表。 */
    @Bean
    public AuthSessionReader authSessionReader(StringRedisTemplate redis,TicketSecurityProperties properties){return new AuthSessionReader(redis,properties);}
    /** 公钥按需获取并缓存；远程获取有超时，网关通过也不能绕过Order独立校验。 */
    @Bean
    public JwtDecoder accessTokenDecoder(TicketSecurityProperties properties,AuthSessionReader sessions){
        var client=HttpClient.newBuilder().connectTimeout(properties.jwkTimeout()).build();
        var factory=new JdkClientHttpRequestFactory(client);factory.setReadTimeout(properties.jwkTimeout());
        var base=NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString()).jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(factory)).build();
        base.setJwtValidator(new AccessTokenClaimsValidator(properties));
        return new SessionCheckingJwtDecoder(base,sessions);
    }
    /** 内部通知凭证与用户JWT使用不同的校验入口。 */
    @Bean
    public PaymentOrderCredential paymentOrderCredential(PaymentOrderCredentialProperties properties)throws Exception{return new PaymentOrderCredential(properties);}
}
