package com.byy.ticket.security.session;

import com.byy.ticket.security.config.TicketSecurityProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/** 网关专用非阻塞会话读取；与Auth共享会话协议，不创建或修改登录状态。 */
public final class ReactiveAuthSessionReader {
    private final ReactiveStringRedisTemplate redis;
    private final TicketSecurityProperties properties;
    /** 注入响应式Redis模板和与Auth一致的键前缀。 */
    public ReactiveAuthSessionReader(ReactiveStringRedisTemplate redis, TicketSecurityProperties properties) {
        this.redis = redis; this.properties = properties;
    }
    /** 缺失会话返回false；Redis故障保留为错误，不能靠签名绕过会话检查。 */
    public Mono<Boolean> isActive(String userId, String sid) {
        String key = properties.redisPrefix() + "{" + userId + "}:session:" + sid;
        return redis.<String, String>opsForHash().get(key, "userId")
                .map(userId::equals).defaultIfEmpty(false)
                .onErrorMap(exception -> new SessionStoreUnavailableException(exception));
    }
}
