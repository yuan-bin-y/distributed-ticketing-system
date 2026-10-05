package com.byy.ticket.auth.service.impl;

import com.byy.ticket.auth.config.AuthProperties;
import com.byy.ticket.auth.dto.*;
import com.byy.ticket.auth.exception.AuthException;
import com.byy.ticket.auth.mapper.UserMapper;
import com.byy.ticket.auth.model.TicketUser;
import com.byy.ticket.auth.service.AuthService;
import com.byy.ticket.auth.token.*;
import com.byy.ticket.auth.vo.*;
import com.byy.ticket.common.result.ApiErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL 保存用户事实，Redis 保存登录会话；登录失败不签发可用 Token。 */
@Service
public class AuthServiceImpl implements AuthService {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final RedisSessionService sessions;
    private final JwtDecoder refreshDecoder;
    private final AuthProperties properties;
    private final String dummyHash;
    /** 注入基础能力；准备虚拟摘要，未知账号也执行一次 BCrypt 比对。 */
    public AuthServiceImpl(UserMapper users, PasswordEncoder passwords, TokenService tokens,
                           RedisSessionService sessions, @Qualifier("refreshTokenDecoder") JwtDecoder refreshDecoder,
                           AuthProperties properties) {
        this.users = users; this.passwords = passwords; this.tokens = tokens; this.sessions = sessions;
        this.refreshDecoder = refreshDecoder; this.properties = properties;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }
    /** 账号统一小写，数据库唯一键保证并发注册只创建一个用户。 */
    @Override
    @Transactional
    public UserVO register(RegisterDTO request) {
        checkPasswordBytes(request.password());
        TicketUser user = new TicketUser();
        user.setUsername(normalize(request.username())); user.setPasswordHash(passwords.encode(request.password()));
        user.setNickname(request.nickname().strip()); user.setStatus("ACTIVE");
        user.setRole("USER"); // 公共注册永远不能自行选择管理员角色。
        if (user.getNickname().isBlank()) { throw new IllegalArgumentException("昵称不能为空"); }
        try { users.insert(user); }
        catch (DuplicateKeyException exception) { throw new AuthException(409, ApiErrorCode.CONFLICT, "账号已存在"); }
        return new UserVO(user.getId(), user.getUsername(), user.getNickname());
    }
    /** 先验证密码和用户状态，再签发 JWT 并保存会话；Redis 失败时不返回成功。 */
    @Override
    public TokenPairVO login(LoginDTO request) {
        checkPasswordBytes(request.password());
        TicketUser user = users.findByUsername(normalize(request.username()));
        boolean matches = passwords.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches || !"ACTIVE".equals(user.getStatus())) { throw AuthException.unauthorized(); }
        String sid = UUID.randomUUID().toString();
        TokenService.IssuedTokens issued = tokens.issue(user.getId(), sid, user.getRole());
        sessions.create(user.getId().toString(), sid, issued.refreshJti(), properties.refreshTtl());
        return issued.tokens();
    }
    /** Refresh 验签后再查用户状态，Lua 核对旧 jti；并发重复刷新仅一个成功。 */
    @Override
    public TokenPairVO refresh(String refreshToken) {
        Jwt jwt;
        try { jwt = refreshDecoder.decode(refreshToken); }
        catch (JwtException | IllegalArgumentException exception) { throw AuthException.unauthorized(); }
        Long userId;
        try { userId = Long.valueOf(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw AuthException.unauthorized(); }
        TicketUser user = users.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) { throw AuthException.unauthorized(); }
        String sid = jwt.getClaimAsString("sid");
        TokenService.IssuedTokens issued = tokens.issue(userId, sid, user.getRole());
        if (!sessions.rotate(userId.toString(), sid, jwt.getId(), issued.refreshJti(), properties.refreshTtl())) {
            throw AuthException.unauthorized();
        }
        return issued.tokens();
    }
    /** Access 已由过滤器验证，删除对应 Redis 会话使该会话的凭证失效。 */
    @Override
    public void logout(Jwt accessToken) { sessions.revoke(accessToken.getSubject(), accessToken.getClaimAsString("sid")); }
    /** 防止 BCrypt 的72字节限制截断中文等多字节密码。 */
    private void checkPasswordBytes(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码 UTF-8 编码不能超过72字节");
        }
    }
    /** 账号大小写不敏感，数据库保存统一的小写形式。 */
    private String normalize(String username) { return username.toLowerCase(Locale.ROOT); }
}
