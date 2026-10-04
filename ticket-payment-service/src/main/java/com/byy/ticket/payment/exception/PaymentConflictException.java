package com.byy.ticket.payment.exception;

/** 同一支付单参数冲突、到期后首次付款或非法冲正时使用的业务异常。 */
public class PaymentConflictException extends RuntimeException {
    /** 保存业务冲突原因，统一异常处理器返回 HTTP 409。 */
    public PaymentConflictException(String message) { super(message); }
}
