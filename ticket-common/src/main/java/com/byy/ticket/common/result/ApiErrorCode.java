package com.byy.ticket.common.result;

/** 通用业务错误码。 */
public enum ApiErrorCode {
    BAD_REQUEST, RESOURCE_NOT_FOUND, METHOD_NOT_ALLOWED, INTERNAL_ERROR,
    UPSTREAM_UNAVAILABLE, UPSTREAM_TIMEOUT, INVALID_UPSTREAM_RESPONSE;

    public String code() {
        return name();
    }
}
