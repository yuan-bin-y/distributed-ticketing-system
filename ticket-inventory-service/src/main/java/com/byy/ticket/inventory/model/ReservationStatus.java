package com.byy.ticket.inventory.model;

/**
 * 库存预留状态：RESERVED 表示暂时占用，SOLD 表示已售出，RELEASED 表示已释放。
 * 只允许 RESERVED 转成 SOLD 或 RELEASED；同一终态的重复操作返回原结果。
 */
public enum ReservationStatus {
    RESERVED, SOLD, RELEASED
}
