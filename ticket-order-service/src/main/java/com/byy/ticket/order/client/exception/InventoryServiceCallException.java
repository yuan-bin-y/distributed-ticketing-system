package com.byy.ticket.order.client.exception;

/**
 * 封装库存远程调用异常，业务冲突与网络失败分别处理，由订单服务统一转换为 HTTP 失败响应。
 * reason 区分不可用、超时或响应不符合契约，库存异常还包含业务冲突。
 */
public class InventoryServiceCallException extends RuntimeException {
    public enum Reason { CONFLICT, UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }

    private final Reason reason;
    private boolean circuitBreakerFailure = true;

    public static InventoryServiceCallException rejectedResponse(String message) {
        var exception=new InventoryServiceCallException(Reason.INVALID_RESPONSE,message);
        exception.circuitBreakerFailure=false; return exception;
    }
    public boolean isCircuitBreakerFailure() { return circuitBreakerFailure; }
    /** 票档额度满仅让原订单稍后重试，不计入整个库存服务的健康失败率。 */
    public static InventoryServiceCallException capacityRejected() {
        var exception=new InventoryServiceCallException(Reason.UNAVAILABLE,"热点库存准入满额，请保留原订单稍后重试");
        exception.circuitBreakerFailure=false;return exception;
    }

    /**
     * 保存失败类别和可返回给调用方的提示。
     */
    public InventoryServiceCallException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * 保存失败类别、提示以及底层异常原因，方便日志定位网络或解析问题。
     */
    public InventoryServiceCallException(Reason reason, String message, Throwable cause) {
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
