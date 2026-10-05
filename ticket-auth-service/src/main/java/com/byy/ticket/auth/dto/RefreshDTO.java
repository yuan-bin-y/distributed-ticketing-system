package com.byy.ticket.auth.dto;

import jakarta.validation.constraints.*;

/** 刷新凭证；不能用 Access Token 代替。 */
public record RefreshDTO(@NotBlank @Size(max=4096) String refreshToken) { }
