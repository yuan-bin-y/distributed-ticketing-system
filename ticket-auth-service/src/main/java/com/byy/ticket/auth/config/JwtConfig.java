package com.byy.ticket.auth.config;

import com.byy.ticket.auth.token.RedisSessionService;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.time.Duration;
import java.util.Base64;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/** 从文件读取 RSA 密钥；Access 校验包含 Redis 会话，Refresh 由 Lua 原子核对。 */
@Configuration
public class JwtConfig {
    /** 读取 PKCS8 私钥和 X509 公钥，检查密钥长度及配对；不在日志中输出密钥。 */
    @Bean
    public RSAKey rsaKey(AuthProperties properties) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                readPem(properties.privateKeyPath(), "PRIVATE KEY")));
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                readPem(properties.publicKeyPath(), "PUBLIC KEY")));
        if (publicKey.getModulus().bitLength() < 2048 || !publicKey.getModulus().equals(privateKey.getModulus())
                || !publicKey.getPublicExponent().equals(privateKey.getPublicExponent())) {
            throw new IllegalArgumentException("RSA 密钥必须配对且不少于2048位");
        }
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("ticket-auth-v1").build();
    }
    /** PEM 内容只从本地文件加载，缺失时停止启动，避免重启后签名密钥变化。 */
    private byte[] readPem(java.nio.file.Path path, String label) throws Exception {
        String value = Files.readString(path).replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(value);
    }
    /** 编码器持有私钥，仅在 Auth 服务签发 Token。 */
    @Bean
    public JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    }
    /** 访问凭证必须为 access，时间、签发方、受众和会话同时有效。 */
    @Bean
    @Primary
    public JwtDecoder jwtDecoder(RSAKey key, AuthProperties properties, RedisSessionService sessions) throws Exception {
        NimbusJwtDecoder decoder = baseDecoder(key, properties, "access");
        OAuth2TokenValidator<Jwt> sessionValidator = jwt -> sessions.active(jwt.getSubject(), jwt.getClaimAsString("sid"))
                ? OAuth2TokenValidatorResult.success() : failure();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validator(properties, "access"), sessionValidator));
        return decoder;
    }
    /** 刷新只验证 JWT 声明；refreshJti 是否仍有效由业务层 Lua 原子判断。 */
    @Bean("refreshTokenDecoder")
    public JwtDecoder refreshTokenDecoder(RSAKey key, AuthProperties properties) throws Exception {
        return baseDecoder(key, properties, "refresh");
    }
    /** 创建仅使用公钥的解码器，接受 RS256。 */
    private NimbusJwtDecoder baseDecoder(RSAKey key, AuthProperties properties, String type) throws Exception {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(validator(properties, type));
        return decoder;
    }
    /** 两种 JWT 都验证过期、issuer、audience、正数用户ID和标准 UUID sid/jti。 */
    private OAuth2TokenValidator<Jwt> validator(AuthProperties properties, String type) {
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && jwt.getSubject() != null && jwt.getSubject().matches("[1-9][0-9]{0,18}")
                    && jwt.getId() != null && uuid(jwt.getId()) && uuid(jwt.getClaimAsString("sid"))
                    && type.equals(jwt.getClaimAsString("tokenType")) && jwt.getAudience().contains(properties.audience());
            return valid ? OAuth2TokenValidatorResult.success() : failure();
        };
        // 无过期宽限：Refresh 到期后不能靠时钟宽限继续刷新。
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(properties.issuer()), claims);
    }
    /** 严格限制会话和凭证编号格式。 */
    private boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    /** 所有声明错误使用统一认证失败。 */
    private OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "登录凭证无效", null));
    }
}
