package com.byy.ticket.common.exception;

/**
 * 表示业务资源不存在；各业务服务的异常处理器把它转换为 HTTP 404。
 */
public class ResourceNotFoundException extends RuntimeException {
    /**
     * 记录资源不存在的具体原因，交给统一异常处理器生成响应。
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
