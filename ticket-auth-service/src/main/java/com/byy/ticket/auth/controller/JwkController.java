package com.byy.ticket.auth.controller;

import com.nimbusds.jose.jwk.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 提供标准 JWKS 公钥文档，供后续服务验证签名；不会返回私钥。 */
@RestController
public class JwkController {
    private final RSAKey key;
    /** 注入签发密钥，仅对外输出公钥部分。 */
    public JwkController(RSAKey key) { this.key = key; }
    /** JWKS 使用标准协议结构，不包装 Result，便于资源服务解码器读取。 */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> publicKeys() { return new JWKSet(key.toPublicJWK()).toJSONObject(); }
}
