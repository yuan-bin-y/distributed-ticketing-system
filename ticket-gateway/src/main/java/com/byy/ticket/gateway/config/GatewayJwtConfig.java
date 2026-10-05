package com.byy.ticket.gateway.config;

import com.byy.ticket.security.config.TicketSecurityProperties;
import com.byy.ticket.security.jwt.*;
import com.byy.ticket.security.session.ReactiveAuthSessionReader;
import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** 创建网关非阻塞JWT解码器：公钥验签、声明校验、Redis会话校验。 */
@Configuration
@EnableConfigurationProperties(TicketSecurityProperties.class)
public class GatewayJwtConfig {
    /** 读取Auth的同一Redis会话键，不读取用户数据库。 */
    @Bean
    public ReactiveAuthSessionReader authSessionReader(ReactiveStringRedisTemplate redis, TicketSecurityProperties properties) {
        return new ReactiveAuthSessionReader(redis, properties);
    }
    /** 公钥按需获取并由Nimbus缓存，显式信任RS256，获取公钥有连接/响应超时。 */
    @Bean
    public ReactiveJwtDecoder accessTokenDecoder(TicketSecurityProperties properties, ReactiveAuthSessionReader sessions) {
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.jwkTimeout().toMillis())
                .responseTimeout(properties.jwkTimeout());
        WebClient web = WebClient.builder().clientConnector(new ReactorClientHttpConnector(client)).build();
        NimbusReactiveJwtDecoder base = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256).webClient(web).build();
        base.setJwtValidator(new AccessTokenClaimsValidator(properties));
        return new SessionCheckingReactiveJwtDecoder(base, sessions);
    }
}
