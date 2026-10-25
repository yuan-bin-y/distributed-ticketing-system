package com.byy.ticket.event.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.event.model.TicketTier;
import org.apache.ibatis.annotations.Mapper;

/**
 * 活动数据库的票档 Mapper，继承 BaseMapper 的按 ID 查询、列表查询、插入和更新等能力。
 * 由 MyBatis-Plus 根据实体映射生成 SQL，不读取其他服务的数据库。
 */
@Mapper
public interface TicketTierMapper extends BaseMapper<TicketTier> {
    /** 单次一致性读取得发布状态与当前规则，保留实时查库的业务语义。 */
    @org.apache.ibatis.annotations.Select("""
            SELECT e.id event_id, s.id session_id, t.id ticket_tier_id, t.name ticket_tier_name, t.price,
                   s.sale_start_time, s.sale_end_time, s.purchase_limit,
                   COALESCE(p.version,0) rule_version, COALESCE(p.max_sessions,2147483647) max_sessions,
                   COALESCE(p.max_per_order,2147483647) max_per_order
            FROM t_ticket_tier t JOIN t_event_session s ON s.id=t.session_id
            JOIN t_event e ON e.id=s.event_id
            LEFT JOIN t_purchase_policy p ON p.event_id=e.id AND p.version=e.purchase_policy_version AND p.status='PUBLISHED'
            WHERE t.id=#{id} AND t.enabled=1 AND s.status='PUBLISHED' AND e.status='PUBLISHED'
            """)
    com.byy.ticket.event.vo.event.TicketPurchaseRuleVO purchaseRule(Long id);
}
