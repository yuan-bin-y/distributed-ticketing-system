package com.byy.ticket.security.session;

/** 会话存储故障，不等同于凭证无效；入口统一返回503且禁止放行。 */
public final class SessionStoreUnavailableException extends RuntimeException {
    /** 保留故障原因供排查，消息不包含Token、密码或会话内容。 */
    public SessionStoreUnavailableException(Throwable cause) { super("登录会话存储暂时不可用", cause); }
}
