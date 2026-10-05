package com.byy.ticket.auth.dto;

import jakarta.validation.constraints.*;

/** 登录请求，与注册使用相同账号规则。 */
public record LoginDTO(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}") String username,
        @NotBlank @Size(max=72) String password) { }
