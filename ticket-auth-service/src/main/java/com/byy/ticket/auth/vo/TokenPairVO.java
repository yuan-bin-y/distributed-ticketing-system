package com.byy.ticket.auth.vo;

/** 登录和刷新响应；expiresIn 是 Access Token 的有效秒数。 */
public record TokenPairVO(String accessToken, String refreshToken, String tokenType, long expiresIn) { }
