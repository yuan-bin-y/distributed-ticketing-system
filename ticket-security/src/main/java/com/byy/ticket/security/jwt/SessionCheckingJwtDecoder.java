package com.byy.ticket.security.jwt;

import com.byy.ticket.security.session.AuthSessionReader;
import org.springframework.security.oauth2.jwt.*;

/** Servlet JwtDecoder包装器：验签和声明通过后，再检查登录会话。 */
public final class SessionCheckingJwtDecoder implements JwtDecoder {
    private final JwtDecoder delegate;
    private final AuthSessionReader sessions;
    /** 使用基础解码器，不能把自身作为delegate。 */
    public SessionCheckingJwtDecoder(JwtDecoder delegate, AuthSessionReader sessions) { this.delegate=delegate; this.sessions=sessions; }
    /** 返回Jwt后由Spring Security建立当前请求Principal，sub就是用户ID。 */
    @Override
    public Jwt decode(String token) {
        Jwt jwt=delegate.decode(token);
        if (!sessions.isActive(jwt.getSubject(),jwt.getClaimAsString("sid"))) { throw new BadJwtException("登录会话已失效"); }
        return jwt;
    }
}
