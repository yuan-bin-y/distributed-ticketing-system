package com.byy.ticket.inventory.admission;

import java.time.LocalDateTime;

/** 已返回成功的准入必须有这份持久化记录；终态记录同时阻止迟到请求重新占票。 */
public record StockAdmission(String orderNo, Long ticketTierId, Long sessionId, Integer quantity,
                             LocalDateTime expiresAt, String status) { }
