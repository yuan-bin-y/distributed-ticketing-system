package com.byy.ticket.order.client.exception;

/**
 * 封装活动远程调用异常，由订单服务统一转换为 HTTP 失败响应。
 * reason 区分不可用、超时或响应不符合契约。
 */
public class EventServiceCallException extends RuntimeException {
    public enum Reason { UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }

    private final Reason reason;

    /**
     * 保存失败类别和可返回给调用方的提示。
     */
    public EventServiceCallException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * 保存失败类别、提示以及底层异常原因，方便日志定位网络或解析问题。
     */
    public EventServiceCallException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * 返回失败类别，统一异常处理器据此选择对应的 HTTP 状态和错误码。
     */
    public Reason getReason() {
        return reason;
    }
}
