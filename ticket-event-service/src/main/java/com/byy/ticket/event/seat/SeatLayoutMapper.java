package com.byy.ticket.event.seat;
import org.apache.ibatis.annotations.*;
@Mapper public interface SeatLayoutMapper {
    record Layout(Long ticketTierId,Long sessionId,String layoutHash,String regionsJson){}
    @Select("SELECT * FROM t_seat_layout WHERE ticket_tier_id=#{tier}") Layout get(long tier);
    @Insert("INSERT INTO t_seat_layout(ticket_tier_id,session_id,layout_hash,regions_json) VALUES(#{ticketTierId},#{sessionId},#{layoutHash},#{regionsJson})") int insert(Layout value);
    @Update("UPDATE t_ticket_tier SET preparation_status='PENDING',preparation_next_at=CURRENT_TIMESTAMP(3),preparation_error=NULL,preparation_token=NULL,preparation_lease_until=NULL WHERE id=#{tier}") int prepare(long tier);
}
