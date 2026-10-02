package com.byy.ticket.common.result;

/** 通用业务错误码。 */
public enum ApiErrorCode {
    BAD_REQUEST, RESOURCE_NOT_FOUND, METHOD_NOT_ALLOWED, INTERNAL_ERROR;

    public String code() {
        return name();
    }
}
