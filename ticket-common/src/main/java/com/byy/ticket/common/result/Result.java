package com.byy.ticket.common.result;

import com.byy.ticket.common.trace.TraceIdContext;
import java.util.Map;

/**
 * 统一 HTTP 响应结构，供 Controller 包装数据，也供远程 Client 解析响应。
 * 
 * @param <T> 业务数据或记录元素的类型。
 * @param code 业务结果码，成功为 OK，失败使用统一错误枚举。
 * @param message 成功说明或可读的错误提示。
 * @param data 成功业务数据；失败通常为空，参数校验失败时为字段错误集合。
 * @param traceId 关联当前请求日志和跨服务调用的追踪编号。
 */
public record Result<T>(String code, String message, T data, String traceId) {
    /**
     * 包装成功数据，业务码为 OK，并附带当前请求的 traceId。
     */
    public static <T> Result<T> success(T data) {
        return new Result<>("OK", "success", data, TraceIdContext.getOrCreate());
    }

    /**
     * 包装失败原因和错误码；data 为空，traceId 用于定位相关日志。
     */
    public static Result<Void> failure(ApiErrorCode code, String message) {
        return new Result<>(code.code(), message, null, TraceIdContext.getOrCreate());
    }

    /**
     * 包装字段校验失败信息，返回字段名与错误提示的对应关系。
     */
    public static Result<Map<String, String>> validation(Map<String, String> errors) {
        return new Result<>(ApiErrorCode.BAD_REQUEST.code(), "请求参数不正确",
                Map.copyOf(errors), TraceIdContext.getOrCreate());
    }
}
