package com.byy.ticket.auth.token;

import com.byy.ticket.auth.config.AuthProperties;
import com.byy.ticket.auth.vo.TokenPairVO;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

/** 私钥签发 RS256 JWT；Access/Refresh 有不同类型及唯一凭证编号。 */
@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    /** 注入 RSA 编码器和签发配置。 */
    public TokenService(JwtEncoder encoder, AuthProperties properties) { this.encoder = encoder; this.properties = properties; }
    /** 同一 sid 签发两个 JWT，供登录建立会话或刷新替换凭证。 */
    public IssuedTokens issue(Long userId, String sid) {
        Instant now = Instant.now();
        String refreshJti = UUID.randomUUID().toString();
        String access = encode(userId, sid, "access", UUID.randomUUID().toString(), now, properties.accessTtl());
        String refresh = encode(userId, sid, "refresh", refreshJti, now, properties.refreshTtl());
        return new IssuedTokens(new TokenPairVO(access, refresh, "Bearer", properties.accessTtl().toSeconds()), refreshJti);
    }
    /** subject 使用用户 ID，业务服务 Principal.getName() 可直接取得同一 ID。 */
    private String encode(Long userId, String sid, String type, String jti, Instant now, Duration ttl) {
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject(userId.toString()).issuedAt(now).expiresAt(now.plus(ttl)).id(jti)
                .claim("sid", sid).claim("tokenType", type).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
    /** 签发内部结果；refreshJti 只用于 Redis，不另行暴露在响应字段。 */
    public record IssuedTokens(TokenPairVO tokens, String refreshJti) { }
}
