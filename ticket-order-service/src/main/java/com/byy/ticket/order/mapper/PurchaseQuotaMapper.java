package com.byy.ticket.order.mapper;

import org.apache.ibatis.annotations.*;

/** Order自己的累计额度：一个用户在同场次的不同票档共用一条记录。 */
@Mapper
public interface PurchaseQuotaMapper {
    /** 首次购买初始化；重复请求保留原数量，唯一键协调并发初始化。 */
    @Insert("""
            INSERT INTO t_user_session_quota(user_id,session_id,occupied_quantity)
            VALUES(#{userId},#{sessionId},0)
            ON DUPLICATE KEY UPDATE occupied_quantity=occupied_quantity
            """)
    int ensureExists(@Param("userId") Long userId,@Param("sessionId") Long sessionId);

    /** 数据库原子判断累计上限；返回1才成功，不能先查询再在Java里加减。 */
    @Update("""
            UPDATE t_user_session_quota SET occupied_quantity=occupied_quantity+#{quantity}
            WHERE user_id=#{userId} AND session_id=#{sessionId}
              AND occupied_quantity+#{quantity}<=#{limit}
            """)
    int occupy(@Param("userId") Long userId,@Param("sessionId") Long sessionId,
               @Param("quantity") Integer quantity,@Param("limit") Integer limit);

    /** 数量归还必须在锁定订单且HELD的事务内调用，本SQL本身不保证幂等。 */
    @Update("""
            UPDATE t_user_session_quota SET occupied_quantity=occupied_quantity-#{quantity}
            WHERE user_id=#{userId} AND session_id=#{sessionId} AND occupied_quantity>=#{quantity}
            """)
    int release(@Param("userId") Long userId,@Param("sessionId") Long sessionId,
                @Param("quantity") Integer quantity);
}
