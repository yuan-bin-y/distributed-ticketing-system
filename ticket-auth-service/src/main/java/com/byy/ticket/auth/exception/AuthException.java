package com.byy.ticket.auth.exception;

import com.byy.ticket.common.result.ApiErrorCode;

/** 认证业务错误，携带 HTTP 状态和统一错误码，不泄露密码和 Token。 */
public class AuthException extends RuntimeException {
    private final int status;
    private final ApiErrorCode code;
    /** 建立可对外返回的认证失败。 */
    public AuthException(int status, ApiErrorCode code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public ApiErrorCode code() { return code; }
    /** 账号、密码、凭证无效统一使用 401。 */
    public static AuthException unauthorized() {
        return new AuthException(401, ApiErrorCode.UNAUTHORIZED, "账号、密码或登录凭证无效");
    }
}
