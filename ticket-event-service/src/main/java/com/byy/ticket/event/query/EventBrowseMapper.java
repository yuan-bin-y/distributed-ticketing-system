package com.byy.ticket.event.query;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.*;
/** 展示接口使用小投影，不把介绍正文、全部票档和内部准备字段装进列表。 */
@Mapper
public interface EventBrowseMapper {
    record SessionSummary(Long id,String name,String venueName,LocalDateTime startTime,LocalDateTime endTime){}
    record TierSummary(Long id,String name,BigDecimal price){}
    @Select("""
        SELECT id,name,venue_name,start_time,end_time FROM t_event_session
        WHERE event_id=#{event} AND status='PUBLISHED' AND id>#{after} ORDER BY id LIMIT #{limit}
        """)
    List<SessionSummary> sessions(@Param("event") long event,@Param("after") long after,@Param("limit") int limit);
    @Select("""
        SELECT t.id,t.name,t.price FROM t_ticket_tier t JOIN t_event_session s ON s.id=t.session_id
        WHERE s.event_id=#{event} AND s.id=#{session} AND s.status='PUBLISHED' AND t.enabled=1 AND t.id>#{after}
        ORDER BY t.id LIMIT #{limit}
        """)
    List<TierSummary> tiers(@Param("event") long event,@Param("session") long session,@Param("after") long after,@Param("limit") int limit);
}
