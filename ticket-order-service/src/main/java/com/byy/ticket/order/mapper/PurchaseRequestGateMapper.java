package com.byy.ticket.order.mapper;
import org.apache.ibatis.annotations.*;
/** 同步与异步入口共用同一用户幂等键的数据库锁，防止跨入口重复建单和占额度。 */
@Mapper public interface PurchaseRequestGateMapper {
    @Insert("INSERT INTO t_purchase_request_gate(user_id,idempotency_key) VALUES(#{user},#{key}) ON DUPLICATE KEY UPDATE user_id=user_id")
    int lock(@Param("user") long user,@Param("key") String key);
}
