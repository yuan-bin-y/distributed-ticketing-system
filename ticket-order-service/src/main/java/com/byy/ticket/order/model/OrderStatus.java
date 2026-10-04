package com.byy.ticket.order.model;

/** 下单状态机；付款依据与库存成交分别处理，不能仅凭通知标记成交。 */
public enum OrderStatus {
    /** 已落库，库存预留结果尚未确定。 */
    STOCK_PENDING,
    /** 库存已预留，支付期限尚未结束。 */
    PENDING_PAYMENT,
    /** 付款依据已可靠保存，后台核对并确认库存；不再按未付款订单关闭。 */
    PAYMENT_CONFIRMING,
    /** 有效付款与原库存售出均已核实，订单成交。 */
    PAID,
    /** 原库存已释放，正在以固定付款编号和原因恢复全额冲正。 */
    REVERSAL_PENDING,
    /** 已核实全额模拟冲正成功，不再交票。 */
    REVERSED,
    /** 已确认首次预留被拒绝，没有可继续的购买。 */
    CREATE_FAILED,
    /** 到期后正在释放库存，失败时继续恢复。 */
    CLOSING,
    /** 已确认库存释放，或没有库存预留的关闭结果。 */
    CLOSED,
    /** 上下游终态不符合当前流程，暂停自动操作并保留证据。 */
    REVIEW_REQUIRED
}
