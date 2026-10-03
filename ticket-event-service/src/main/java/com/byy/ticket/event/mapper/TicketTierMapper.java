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
}
