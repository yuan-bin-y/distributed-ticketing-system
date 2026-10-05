package com.byy.ticket.event.mapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 持久化准备进度；领取及结果按令牌更新，进程退出后租约到期恢复。 */
@Mapper
public interface PreparationMapper {
    /** 限量扫描到期且租约可领取的草稿票档，避免一次加载全部任务。 */
    @Select("""
        SELECT t.id FROM t_ticket_tier t JOIN t_event_session s ON s.id=t.session_id JOIN t_event e ON e.id=s.event_id
        WHERE t.preparation_status='PENDING' AND e.status='DRAFT' AND t.preparation_next_at<=CURRENT_TIMESTAMP(3)
          AND (t.preparation_lease_until IS NULL OR t.preparation_lease_until<=CURRENT_TIMESTAMP(3))
        ORDER BY t.preparation_next_at,t.id LIMIT 50
        """)
    List<Long> selectDue();
    /** 原子领取并增加尝试次数，重启后等待租约到期即可继续。 */
    @Update("""
        UPDATE t_ticket_tier SET preparation_token=#{token},preparation_lease_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(3)),
            preparation_attempts=preparation_attempts+1
        WHERE id=#{id} AND preparation_status='PENDING' AND preparation_next_at<=CURRENT_TIMESTAMP(3)
          AND (preparation_lease_until IS NULL OR preparation_lease_until<=CURRENT_TIMESTAMP(3))
        """)
    int claim(@Param("id")Long id,@Param("token")String token);
    /** 只有当前领取者可以保存READY、待核对或重试进度。 */
    @Update("""
        UPDATE t_ticket_tier SET preparation_status=#{status},preparation_error=#{error},
            preparation_next_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3)),preparation_token=NULL,preparation_lease_until=NULL
        WHERE id=#{id} AND preparation_status='PENDING' AND preparation_token=#{token}
        """)
    int finish(@Param("id")Long id,@Param("token")String token,@Param("status")String status,@Param("error")String error,@Param("delay")int delay);
    /** 管理员仅能用原参数重新准备草稿，不覆盖仍被领取的任务。 */
    @Update("""
        UPDATE t_ticket_tier t JOIN t_event_session s ON s.id=t.session_id
        SET t.preparation_status='PENDING',t.preparation_next_at=CURRENT_TIMESTAMP(3),t.preparation_error=NULL
        WHERE s.event_id=#{eventId} AND t.preparation_status='REVIEW_REQUIRED'
        """)
    int retryReviewed(Long eventId);
}
