package com.byy.ticket.order.purchase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper public interface PurchaseAcceptanceMapper extends BaseMapper<PurchaseAcceptance> {
    @Select("SELECT * FROM t_purchase_acceptance WHERE user_id=#{user} AND idempotency_key=#{key}")
    PurchaseAcceptance byKey(@Param("user") long user,@Param("key") String key);
    @Select("SELECT * FROM t_purchase_acceptance WHERE request_id=#{id} FOR UPDATE") PurchaseAcceptance lock(String id);
    @Update("""
        UPDATE t_purchase_acceptance SET lease_token=#{token},lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)),attempt_count=attempt_count+1
        WHERE request_id=#{id} AND status IN ('ACCEPTED','CANCELLING')
        AND (next_attempt_at<=CURRENT_TIMESTAMP(3) OR attempt_count=0)
        AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(3))
        """) int claim(@Param("id") String id,@Param("token") String token);
    @Update("""
        UPDATE t_purchase_acceptance SET status=#{status},last_error=#{error},lease_token=NULL,lease_until=NULL,
        next_attempt_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3)) WHERE request_id=#{id} AND lease_token=#{token}
        """) int finish(@Param("id") String id,@Param("token") String token,@Param("status") String status,@Param("error") String error,@Param("delay") int delay);
    @Select("""
        SELECT a.request_id FROM t_purchase_acceptance a WHERE a.status IN ('ACCEPTED','CANCELLING')
        AND a.next_attempt_at<=CURRENT_TIMESTAMP(3) AND (a.lease_until IS NULL OR a.lease_until<=CURRENT_TIMESTAMP(3))
        AND (a.status='CANCELLING' OR a.expires_at<=CURRENT_TIMESTAMP(3)
          OR EXISTS(SELECT 1 FROM t_order_outbox b WHERE b.request_id=a.request_id AND b.status='PUBLISHED'))
        ORDER BY a.next_attempt_at LIMIT 20
        """) List<String> due();
}
