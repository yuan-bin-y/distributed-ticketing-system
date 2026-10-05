package com.byy.ticket.security.session;

import com.byy.ticket.security.config.TicketSecurityProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;

/** Servlet服务同步读取会话，与网关响应式读取器使用相同键和用户归属规则。 */
public final class AuthSessionReader {
    private final StringRedisTemplate redis;
    private final TicketSecurityProperties properties;
    /** 注入Redis及共享会话配置，不访问Auth数据库。 */
    public AuthSessionReader(StringRedisTemplate redis, TicketSecurityProperties properties) { this.redis=redis; this.properties=properties; }
    /** 会话不存在为false；Redis故障抛独立异常，不能跳过会话验证。 */
    public boolean isActive(String userId, String sid) {
        try { return userId.equals(redis.opsForHash().get(properties.redisPrefix()+"{"+userId+"}:session:"+sid,"userId")); }
        catch (DataAccessException exception) { throw new SessionStoreUnavailableException(exception); }
    }
}
