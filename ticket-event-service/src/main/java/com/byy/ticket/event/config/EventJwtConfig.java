package com.byy.ticket.event.config;

import com.byy.ticket.security.config.TicketSecurityProperties;
import com.byy.ticket.security.jwt.*;
import com.byy.ticket.security.session.AuthSessionReader;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestTemplate;

/** Event公钥验签、声明和Redis会话校验，复用Order及Gateway的身份规则。 */
@Configuration
@EnableConfigurationProperties(TicketSecurityProperties.class)
public class EventJwtConfig {
    /** 同步Servlet请求读取共享登录会话，不读取Auth用户数据库。 */
    @Bean
    public AuthSessionReader authSessionReader(StringRedisTemplate redis,TicketSecurityProperties properties){return new AuthSessionReader(redis,properties);}
    /** 只持有公钥，获取设置超时并由Nimbus缓存；校验成功后框架自动建立Principal。 */
    @Bean
    public JwtDecoder accessTokenDecoder(TicketSecurityProperties properties,AuthSessionReader sessions){
        var client=HttpClient.newBuilder().connectTimeout(properties.jwkTimeout()).build();
        var factory=new JdkClientHttpRequestFactory(client);factory.setReadTimeout(properties.jwkTimeout());
        var base=NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString()).jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(factory)).build();
        base.setJwtValidator(new AccessTokenClaimsValidator(properties));
        return new SessionCheckingJwtDecoder(base,sessions);
    }
}
