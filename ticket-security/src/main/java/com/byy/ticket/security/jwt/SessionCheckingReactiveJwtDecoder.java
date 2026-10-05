package com.byy.ticket.security.jwt;

import com.byy.ticket.security.session.ReactiveAuthSessionReader;
import org.springframework.security.oauth2.jwt.*;
import reactor.core.publisher.Mono;

/** 在公钥验签、声明校验后串接异步Redis校验；无需block或手工subscribe。 */
public final class SessionCheckingReactiveJwtDecoder implements ReactiveJwtDecoder {
    private final ReactiveJwtDecoder delegate;
    private final ReactiveAuthSessionReader sessions;
    /** 包装基础解码器，不能把本包装器本身作为delegate。 */
    public SessionCheckingReactiveJwtDecoder(ReactiveJwtDecoder delegate, ReactiveAuthSessionReader sessions) {
        this.delegate = delegate; this.sessions = sessions;
    }
    /** 只有会话有效才返回Jwt供Security建立Principal；无效凭证返回401，存储异常上抛。 */
    @Override
    public Mono<Jwt> decode(String token) {
        return delegate.decode(token).flatMap(jwt -> sessions.isActive(jwt.getSubject(), jwt.getClaimAsString("sid"))
                .flatMap(active -> active ? Mono.just(jwt) : Mono.error(new BadJwtException("登录会话已失效"))));
    }
}
