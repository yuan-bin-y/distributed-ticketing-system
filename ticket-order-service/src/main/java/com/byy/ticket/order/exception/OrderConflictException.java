package com.byy.ticket.order.exception;

/** 同一购买幂等键被用于不同购买参数，返回 409。 */
public class OrderConflictException extends RuntimeException {
    /** 保存业务冲突提示。 */
    public OrderConflictException(String message) { super(message); }
}
