package com.byy.ticket.auth.dto;

import jakarta.validation.constraints.*;

/** 注册输入：密码仅在请求中使用，不记录到日志。 */
public record RegisterDTO(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}", message="账号为3至32位字母、数字或下划线") String username,
        @NotBlank @Size(min=8, max=72, message="密码长度为8至72个字符") String password,
        @NotBlank @Size(max=64) String nickname) { }
