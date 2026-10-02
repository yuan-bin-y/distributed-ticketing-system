package com.byy.ticket.order.client.exception;

/** 区分依赖不可用、调用超时以及响应契约异常。 */
public class EventServiceCallException extends RuntimeException {
    public enum Reason { UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }

    private final Reason reason;

    public EventServiceCallException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public EventServiceCallException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
