package com.byy.ticket.event.exception;
/** 草稿幂等参数、准备状态或发布条件冲突，返回409。 */
public class EventConflictException extends RuntimeException {
    public EventConflictException(String message){super(message);}
}
