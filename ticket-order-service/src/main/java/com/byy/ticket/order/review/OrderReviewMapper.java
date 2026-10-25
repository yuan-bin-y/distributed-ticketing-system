package com.byy.ticket.order.review;

import com.byy.ticket.order.model.TicketOrder;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 管理员处理命令与审计共用一条记录，原操作编号在重试和接管期间保持不变。 */
@Mapper
public interface OrderReviewMapper {
    record Action(String id, Long orderId, String requestKey, Long operatorId, String action,
                  String reason, String status, String leaseToken, int attemptCount, LocalDateTime nextAttemptAt,
                  String factsJson, String resultStatus, String lastError, LocalDateTime createdAt) {}

    @Select("SELECT * FROM t_order WHERE status='REVIEW_REQUIRED' AND id>#{after} ORDER BY id LIMIT #{limit}")
    List<TicketOrder> orders(@Param("after") long after, @Param("limit") int limit);
    @Select("SELECT * FROM t_order_review_action WHERE id=#{id}") Action get(String id);
    @Select("SELECT * FROM t_order_review_action WHERE id=#{id} FOR UPDATE") Action lock(String id);
    @Select("SELECT * FROM t_order_review_action WHERE order_id=#{order} AND request_key=#{key}")
    Action byKey(@Param("order") long order, @Param("key") String key);
    @Select("SELECT * FROM t_order_review_action WHERE order_id=#{order} ORDER BY created_at DESC,id DESC LIMIT 50")
    List<Action> history(long order);
    @Insert("INSERT INTO t_order_review_action(id,order_id,request_key,operator_id,action,reason) VALUES(#{id},#{order},#{key},#{operator},#{action},#{reason})")
    int insert(@Param("id") String id, @Param("order") long order, @Param("key") String key,
               @Param("operator") long operator, @Param("action") String action, @Param("reason") String reason);
    @Select("SELECT id FROM t_order_review_action WHERE status IN ('PENDING','RUNNING') AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER BY next_attempt_at,id LIMIT 20")
    List<String> due();
    @Update("UPDATE t_order_review_action SET status='RUNNING',lease_token=#{token},attempt_count=attempt_count+1,next_attempt_at=TIMESTAMPADD(SECOND,65,CURRENT_TIMESTAMP(3)) WHERE id=#{id}")
    int running(@Param("id") String id, @Param("token") String token);
    @Update("UPDATE t_order_review_action SET status=#{status},facts_json=#{facts},result_status=#{result},last_error=#{error},next_attempt_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3)) WHERE id=#{id}")
    int finish(@Param("id") String id, @Param("status") String status, @Param("facts") String facts,
               @Param("result") String result, @Param("error") String error, @Param("delay") int delay);
    @Update("UPDATE t_order SET lease_token=#{token},lease_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(3)) WHERE id=#{id} AND status='REVIEW_REQUIRED' AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP(3))")
    int claim(@Param("id") long id, @Param("token") String token);
    @Update("UPDATE t_order SET reservation_id=#{reservation} WHERE id=#{id} AND status='REVIEW_REQUIRED' AND lease_token=#{token}")
    int reservation(@Param("id") long id, @Param("token") String token, @Param("reservation") String reservation);
}
