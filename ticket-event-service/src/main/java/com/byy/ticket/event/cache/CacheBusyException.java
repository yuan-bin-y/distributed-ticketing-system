package com.byy.ticket.event.cache;
/** 重建等待或回源并发达到上限时返回503。 */
public class CacheBusyException extends RuntimeException {
    public CacheBusyException(){super("活动正在加载，请稍后重试");}
}
