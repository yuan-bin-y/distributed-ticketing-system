package com.byy.ticket.order.vo.order;

import java.math.BigDecimal;

/** 购票预览结果，后续实际下单仍需重新校验并预留库存。 */
public record OrderPreviewVO(
        Long eventId, Long sessionId, Long ticketTierId, String ticketTierName,
        BigDecimal unitPrice, Integer quantity, BigDecimal totalAmount
) {
}
