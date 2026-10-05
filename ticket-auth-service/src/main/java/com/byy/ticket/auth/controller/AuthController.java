package com.byy.ticket.auth.controller;

import com.byy.ticket.auth.dto.*;
import com.byy.ticket.auth.service.AuthService;
import com.byy.ticket.auth.vo.*;
import com.byy.ticket.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 用户认证入口；只收取输入、委托业务并包装统一响应。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    /** 构造器注入认证业务。 */
    public AuthController(AuthService auth) { this.auth = auth; }
    /** POST 注册账号，返回无密码的用户资料。 */
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO request) { return Result.success(auth.register(request)); }
    /** POST 账号密码登录，返回访问凭证和刷新凭证。 */
    @PostMapping("/login")
    public Result<TokenPairVO> login(@Valid @RequestBody LoginDTO request) { return Result.success(auth.login(request)); }
    /** POST 刷新凭证，成功后必须保存新 Refresh Token。 */
    @PostMapping("/refresh")
    public Result<TokenPairVO> refresh(@Valid @RequestBody RefreshDTO request) { return Result.success(auth.refresh(request.refreshToken())); }
    /** POST 当前会话退出；用户ID和sid来自已验签 JWT，不接受正文指定。 */
    @PostMapping("/logout")
    public Result<Void> logout(@AuthenticationPrincipal Jwt jwt) { auth.logout(jwt); return Result.success(null); }
    /** 仅检查服务已启动，不代表 MySQL/Redis 等所有依赖都健康。 */
    @GetMapping("/ping")
    public Result<Map<String, String>> ping() { return Result.success(Map.of("service", "ticket-auth-service", "status", "ok")); }
}
