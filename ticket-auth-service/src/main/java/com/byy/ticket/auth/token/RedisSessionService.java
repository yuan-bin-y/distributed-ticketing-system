package com.byy.ticket.auth.token;

import com.byy.ticket.auth.config.AuthProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 每次登录建立独立会话；Lua 防止两个刷新请求同时使用同一凭证。 */
@Service
public class RedisSessionService {
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('HSET', KEYS[1], 'userId', ARGV[1])
            redis.call('HSET', KEYS[1], 'refreshJti', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ROTATE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'userId') ~= ARGV[1]
                or redis.call('HGET', KEYS[1], 'refreshJti') ~= ARGV[2] then return 0 end
            redis.call('HSET', KEYS[1], 'refreshJti', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    /** 注入 Redis 与应用独立前缀。 */
    public RedisSessionService(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis; this.properties = properties;
    }
    /** 登录时原子保存用户、当前刷新凭证编号与有效期。 */
    public void create(String userId, String sid, String refreshJti, Duration ttl) {
        Long result = redis.execute(CREATE, List.of(key(userId, sid)), userId, refreshJti, Long.toString(ttl.toMillis()));
        if (!Long.valueOf(1).equals(result)) { throw new IllegalStateException("登录会话编号冲突"); }
    }
    /** 会话被删除或过期后，旧 Access Token 即使签名正确也不能继续访问。 */
    public boolean active(String userId, String sid) {
        return userId != null && sid != null && userId.equals(redis.opsForHash().get(key(userId, sid), "userId"));
    }
    /** 比较并替换 refreshJti；并发刷新只有一个请求成功，退出后也无法复活会话。 */
    public boolean rotate(String userId, String sid, String oldJti, String newJti, Duration ttl) {
        return Long.valueOf(1).equals(redis.execute(ROTATE, List.of(key(userId, sid)), userId, oldJti, newJti,
                Long.toString(ttl.toMillis())));
    }
    /** 删除当前会话，两种 Token 一起失效，不影响其他设备登录。 */
    public void revoke(String userId, String sid) { redis.delete(key(userId, sid)); }
    /** 用户哈希标签使同一用户的键可用于 Redis Cluster；测试使用独立前缀。 */
    private String key(String userId, String sid) { return properties.redisPrefix() + "{" + userId + "}:session:" + sid; }
}
