package com.byy.ticket.order.purchase;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper public interface PurchaseOutboxMapper {
    record Event(String eventId,String requestId,String status,Integer attemptCount,String leaseToken){}
    @Insert("INSERT INTO t_order_outbox(event_id,request_id) VALUES(#{event},#{request})") int insert(@Param("event") String event,@Param("request") String request);
    @Select("SELECT event_id,request_id,status,attempt_count,lease_token FROM t_order_outbox WHERE event_id=#{id}") Event get(String id);
    @Select("SELECT event_id FROM t_order_outbox WHERE status IN ('PENDING','PUBLISHING') AND next_attempt_at<=CURRENT_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(3)) ORDER BY next_attempt_at LIMIT 20") List<String> due();
    @Update("UPDATE t_order_outbox SET status='PUBLISHING',attempt_count=attempt_count+1,lease_token=#{token},lease_until=TIMESTAMPADD(SECOND,15,CURRENT_TIMESTAMP(3)) WHERE event_id=#{id} AND status IN ('PENDING','PUBLISHING') AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(3)) AND next_attempt_at<=CURRENT_TIMESTAMP(3)")
    int claim(@Param("id") String id,@Param("token") String token);
    @Update("UPDATE t_order_outbox SET status=#{status},last_error=#{error},lease_token=NULL,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3)) WHERE event_id=#{id} AND lease_token=#{token}")
    int finish(@Param("id") String id,@Param("token") String token,@Param("status") String status,@Param("error") String error,@Param("delay") int delay);
    @Select("SELECT event_id,request_id,status,attempt_count,lease_token FROM t_order_outbox WHERE status='REVIEW_REQUIRED' ORDER BY created_at LIMIT 100") List<Event> reviewed();
    @Select("SELECT event_id,request_id,status,attempt_count,lease_token FROM t_order_outbox WHERE event_id=#{id} FOR UPDATE") Event lock(String id);
    @Update("UPDATE t_order_outbox SET status='PENDING',attempt_count=0,next_attempt_at=CURRENT_TIMESTAMP(3),last_error=NULL WHERE event_id=#{id} AND status='REVIEW_REQUIRED'") int replay(String id);
    @Insert("INSERT INTO t_order_outbox_replay(event_id,operator_id,reason) VALUES(#{id},#{user},#{reason})") int audit(@Param("id") String id,@Param("user") long user,@Param("reason") String reason);
}
