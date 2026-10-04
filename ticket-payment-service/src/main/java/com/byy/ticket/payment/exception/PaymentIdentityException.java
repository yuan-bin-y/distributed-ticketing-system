package com.byy.ticket.payment.exception;

/** 公共支付接口没有可信用户身份。 */
public class PaymentIdentityException extends RuntimeException {
    /** 提供通用身份错误，不泄露支付单归属。 */
    public PaymentIdentityException() { super("请提供有效用户身份"); }
}
