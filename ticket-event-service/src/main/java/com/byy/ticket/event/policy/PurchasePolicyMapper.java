package com.byy.ticket.event.policy;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface PurchasePolicyMapper {
    @Select("SELECT * FROM t_purchase_policy WHERE event_id=#{event} AND version=#{version}")
    PurchasePolicy get(@Param("event") long event,@Param("version") long version);
    @Select("SELECT COALESCE(MAX(version),0)+1 FROM t_purchase_policy WHERE event_id=#{id}") long next(long id);
    @Select("SELECT * FROM t_purchase_policy WHERE event_id=#{id} ORDER BY version DESC LIMIT 100") List<PurchasePolicy> list(long id);
    @Insert("INSERT INTO t_purchase_policy(event_id,version,max_sessions,max_per_order,status,note) VALUES(#{eventId},#{version},#{maxSessions},#{maxPerOrder},#{status},#{note})") int insert(PurchasePolicy policy);
    @Select("SELECT p.* FROM t_purchase_policy p JOIN t_event e ON p.event_id=e.id AND p.version=e.purchase_policy_version WHERE e.id=#{event} AND p.status='PUBLISHED'") PurchasePolicy active(long event);
    @Update("UPDATE t_purchase_policy SET status='PUBLISHED' WHERE event_id=#{event} AND version=#{version} AND status='DRAFT'") int publish(@Param("event") long event,@Param("version") long version);
    @Update("UPDATE t_event SET purchase_policy_version=#{version} WHERE id=#{event}") int activate(@Param("event") long event,@Param("version") long version);
}
