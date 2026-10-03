package com.byy.ticket.common.result;

/**
 * 各服务统一使用的失败码；HTTP 状态表达请求结果，code 表达具体错误类别。
 */
public enum ApiErrorCode {
    BAD_REQUEST, CONFLICT, SERVICE_BUSY, RESOURCE_NOT_FOUND, METHOD_NOT_ALLOWED, INTERNAL_ERROR,
    UPSTREAM_UNAVAILABLE, UPSTREAM_TIMEOUT, INVALID_UPSTREAM_RESPONSE;

    /**
     * 返回枚举名称作为 JSON 响应中的业务错误码。
     */
    public String code() {
        return name();
    }
}
