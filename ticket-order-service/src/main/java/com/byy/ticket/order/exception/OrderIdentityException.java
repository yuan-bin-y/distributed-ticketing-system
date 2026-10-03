package com.byy.ticket.order.exception;

/** 下单和订单查询没有可用身份时返回 401。 */
public class OrderIdentityException extends RuntimeException {
    /** 保存缺少身份的提示。 */
    public OrderIdentityException() { super("需要登录；本地验证可显式开启开发身份配置"); }
}
