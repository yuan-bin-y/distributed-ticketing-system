package com.byy.ticket.event.vo.event;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDateTime;
/** 管理端可查看草稿、准备进度和有界错误；不暴露任务令牌或服务凭证。 */
public record EventDraftVO(Long eventId,String name,String status,List<Session> sessions) {
    public record Session(Long sessionId,String name,String status,LocalDateTime startTime,LocalDateTime endTime,
                          LocalDateTime saleStartTime,LocalDateTime saleEndTime,Integer purchaseLimit,List<Tier> ticketTiers) { }
    public record Tier(Long ticketTierId,String name,BigDecimal price,Integer plannedQuantity,String preparationStatus,
                       Integer attempts,String lastError) { }
}
