package com.byy.ticket.order.model;

/** 下单状态机；本阶段没有支付成功入口，库存 SOLD 需人工核对，不能推断订单已支付。 */
public enum OrderStatus {
    /** 已落库，库存预留结果尚未确定。 */
    STOCK_PENDING,
    /** 库存已预留，支付期限尚未结束。 */
    PENDING_PAYMENT,
    /** 已确认首次预留被拒绝，没有可继续的购买。 */
    CREATE_FAILED,
    /** 到期后正在释放库存，失败时继续恢复。 */
    CLOSING,
    /** 已确认库存释放，或没有库存预留的关闭结果。 */
    CLOSED,
    /** 上下游终态不符合当前流程，暂停自动操作并保留证据。 */
    REVIEW_REQUIRED
}
