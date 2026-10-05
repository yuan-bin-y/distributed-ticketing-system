package com.byy.ticket.auth.vo;

/** 对外用户资料，不含密码摘要。 */
public record UserVO(Long id, String username, String nickname) { }
