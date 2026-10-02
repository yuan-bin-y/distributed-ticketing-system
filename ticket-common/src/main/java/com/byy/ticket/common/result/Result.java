package com.byy.ticket.common.result;

import com.byy.ticket.common.trace.TraceIdContext;
import java.util.Map;

/** 与电表项目一致的统一响应结构。 */
public record Result<T>(String code, String message, T data, String traceId) {
    public static <T> Result<T> success(T data) {
        return new Result<>("OK", "success", data, TraceIdContext.getOrCreate());
    }

    public static Result<Void> failure(ApiErrorCode code, String message) {
        return new Result<>(code.code(), message, null, TraceIdContext.getOrCreate());
    }

    public static Result<Map<String, String>> validation(Map<String, String> errors) {
        return new Result<>(ApiErrorCode.BAD_REQUEST.code(), "请求参数不正确",
                Map.copyOf(errors), TraceIdContext.getOrCreate());
    }
}
