package com.byy.ticket.inventory.vo.inventory;

/**
 * 内部查询返回的票档库存计数，用于核对数量变化。
 * 
 * @param ticketTierId 票档 ID，用于关联规则与库存。
 * @param sessionId 所属场次 ID，跨服务传递时作为业务标识。
 * @param totalQuantity 该票档初始化的总库存。
 * @param availableQuantity 尚未被占用，可以继续预留的数量。
 * @param reservedQuantity 已预留但尚未确认售出的数量。
 * @param soldQuantity 已经确认售出的数量。
 */
public record TicketStockVO(Long ticketTierId, Long sessionId, Integer totalQuantity,
                            Integer availableQuantity, Integer reservedQuantity, Integer soldQuantity) {
}
