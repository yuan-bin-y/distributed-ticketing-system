package com.byy.ticket.order.client.exception;

/** 支付HTTP业务冲突、网络故障和响应契约错误，交由订单统一异常处理器转换。 */
public class PaymentServiceCallException extends RuntimeException {
    /** 区分业务冲突、不可用、超时与无效响应，供调用方判断是否可以原请求重试。 */
    public enum Reason { CONFLICT, UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }
    private final Reason reason;

    /** 保存失败类别和可读提示。 */
    public PaymentServiceCallException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** 保存底层原因，网络故障不等于支付单没有创建成功。 */
    public PaymentServiceCallException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** 获取分类，选择409、503、504或502响应。 */
    public Reason getReason() { return reason; }
}
