package com.byy.ticket.inventory.exception;

/**
 * 表示库存不足、请求参数与原预留冲突或不允许的状态转换，统一返回 HTTP 409。
 */
public class InventoryConflictException extends RuntimeException {
    /**
     * 记录库存业务冲突的原因，由统一异常处理器生成失败响应。
     */
    public InventoryConflictException(String message) {
        super(message);
    }
}
