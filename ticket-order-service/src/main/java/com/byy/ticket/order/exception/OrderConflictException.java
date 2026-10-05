package com.byy.ticket.order.exception;

/** 购买幂等参数、累计额度或订单状态冲突，返回409。 */
public class OrderConflictException extends RuntimeException {
    /** 保存业务冲突提示。 */
    public OrderConflictException(String message) { super(message); }
}
