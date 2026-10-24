package com.byy.ticket.order.mapper;
import org.apache.ibatis.annotations.*;
/** 用户活动额度行作为并发边界；同一场次多张订单只占一个场次名额。 */
@Mapper
public interface EventPurchaseQuotaMapper {
    @Insert("INSERT INTO t_user_event_quota(user_id,event_id) VALUES(#{user},#{event}) ON DUPLICATE KEY UPDATE user_id=user_id")
    int ensure(@Param("user") long user,@Param("event") long event);
    @Select("SELECT occupied_sessions FROM t_user_event_quota WHERE user_id=#{user} AND event_id=#{event} FOR UPDATE")
    int lock(@Param("user") long user,@Param("event") long event);
    @Insert("INSERT INTO t_user_event_session(user_id,event_id,session_id) VALUES(#{user},#{event},#{session}) ON DUPLICATE KEY UPDATE user_id=user_id")
    int ensureSession(@Param("user") long user,@Param("event") long event,@Param("session") long session);
    @Select("SELECT active_orders FROM t_user_event_session WHERE user_id=#{user} AND event_id=#{event} AND session_id=#{session} FOR UPDATE")
    int sessionOrders(@Param("user") long user,@Param("event") long event,@Param("session") long session);
    @Update("UPDATE t_user_event_quota SET occupied_sessions=occupied_sessions+1 WHERE user_id=#{user} AND event_id=#{event} AND occupied_sessions<#{limit}")
    int occupy(@Param("user") long user,@Param("event") long event,@Param("limit") int limit);
    @Update("UPDATE t_user_event_session SET active_orders=active_orders+1 WHERE user_id=#{user} AND event_id=#{event} AND session_id=#{session}")
    int addOrder(@Param("user") long user,@Param("event") long event,@Param("session") long session);
    @Update("UPDATE t_user_event_session SET active_orders=active_orders-1 WHERE user_id=#{user} AND event_id=#{event} AND session_id=#{session} AND active_orders>0")
    int removeOrder(@Param("user") long user,@Param("event") long event,@Param("session") long session);
    @Update("UPDATE t_user_event_quota SET occupied_sessions=occupied_sessions-1 WHERE user_id=#{user} AND event_id=#{event} AND occupied_sessions>0")
    int release(@Param("user") long user,@Param("event") long event);
}
