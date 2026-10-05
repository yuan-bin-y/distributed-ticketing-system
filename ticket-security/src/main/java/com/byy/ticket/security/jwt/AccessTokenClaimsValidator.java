package com.byy.ticket.security.jwt;

import com.byy.ticket.security.config.TicketSecurityProperties;
import java.time.Duration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/** 可由网关和后续业务服务复用的Access声明规则；本类不执行Redis或其他网络操作。 */
public final class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {
    private final OAuth2TokenValidator<Jwt> delegate;
    /** 组合无过期宽限的时间、issuer与自定义声明校验。 */
    public AccessTokenClaimsValidator(TicketSecurityProperties properties) {
        delegate = new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(properties.issuer()), jwt -> validateClaims(jwt, properties));
    }
    /** 对已经验签的JWT验证声明。 */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) { return delegate.validate(jwt); }
    /** 验证正数用户ID、UUID会话/凭证编号、Access类型及受众；Refresh不能用于访问业务。 */
    private OAuth2TokenValidatorResult validateClaims(Jwt jwt, TicketSecurityProperties properties) {
        boolean valid;
        try {
            valid = jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt()) && positiveUserId(jwt.getSubject())
                    && uuid(jwt.getId()) && uuid(jwt.getClaimAsString("sid"))
                    && "access".equals(jwt.getClaimAsString("tokenType"))
                    && (jwt.getClaim("role") == null || "USER".equals(jwt.getClaim("role")) || "ADMIN".equals(jwt.getClaim("role")))
                    && jwt.getAudience() != null && jwt.getAudience().contains(properties.audience());
        } catch (RuntimeException exception) { valid = false; }
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "访问凭证声明无效", null));
    }
    /** 用户ID必须符合业务层Long范围，避免溢出ID通过入口校验。 */
    private boolean positiveUserId(String value) {
        try { return value != null && value.matches("[1-9][0-9]{0,18}") && Long.parseLong(value) > 0; }
        catch (NumberFormatException exception) { return false; }
    }
    /** 标准UUID格式也避免把任意外部输入拼成Redis键。 */
    private boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
