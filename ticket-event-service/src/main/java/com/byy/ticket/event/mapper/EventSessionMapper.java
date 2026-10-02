package com.byy.ticket.event.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.event.model.EventSession;
import org.apache.ibatis.annotations.Mapper;

/** 单表操作使用 MyBatis-Plus 通用方法。 */
@Mapper
public interface EventSessionMapper extends BaseMapper<EventSession> {
}
