package com.byy.ticket.auth.service;

import com.byy.ticket.auth.dto.*;
import com.byy.ticket.auth.vo.*;
import org.springframework.security.oauth2.jwt.Jwt;

/** 注册、登录、凭证轮换和当前会话退出。 */
public interface AuthService {
    /** 创建用户，不自动登录。 */
    UserVO register(RegisterDTO request);
    /** 比对账号密码，建立 Redis 会话并签发凭证。 */
    TokenPairVO login(LoginDTO request);
    /** 原子替换刷新凭证，旧 Refresh Token 立即失效。 */
    TokenPairVO refresh(String refreshToken);
    /** 撤销当前已认证会话。 */
    void logout(Jwt accessToken);
}
