package com.byy.ticket.security.jwt;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** 只有经过验签和会话校验的JWT角色才成为权限；旧Token缺少role按普通用户处理。 */
public final class UserRoleAuthorities {
    private UserRoleAuthorities() { }
    public static JwtAuthenticationConverter converter() {
        var converter=new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority(
                "ADMIN".equals(jwt.getClaimAsString("role")) ? "ROLE_ADMIN" : "ROLE_USER")));
        return converter;
    }
}
