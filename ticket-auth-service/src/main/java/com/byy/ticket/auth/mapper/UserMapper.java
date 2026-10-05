package com.byy.ticket.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byy.ticket.auth.model.TicketUser;
import org.apache.ibatis.annotations.Select;

/** 只访问 Auth 自己的用户表。 */
public interface UserMapper extends BaseMapper<TicketUser> {
    /** 账号先由业务层统一为小写，精确查找登录用户。 */
    @Select("SELECT * FROM t_user WHERE username = #{username}")
    TicketUser findByUsername(String username);
}
