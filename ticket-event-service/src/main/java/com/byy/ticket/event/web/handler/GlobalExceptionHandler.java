package com.byy.ticket.event.web.handler;

import com.byy.ticket.common.result.ApiErrorCode;
import com.byy.ticket.common.result.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一处理活动服务 Controller 和业务层抛出的异常，生成约定的 HTTP 状态与 Result 响应。 业务代码负责抛出异常，Controller 不必逐个捕获并拼装错误 JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler
        extends com.byy.ticket.observability.web.ServletExceptionAdvice {
    @ExceptionHandler(com.byy.ticket.event.cache.CacheBusyException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleCacheBusy(RuntimeException exception) {
        return Result.failure(ApiErrorCode.SERVICE_BUSY, exception.getMessage());
    }

    /** 幂等参数、库存准备或发布条件冲突，不返回假发布成功。 */
    @ExceptionHandler(com.byy.ticket.event.exception.EventConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleConflict(RuntimeException exception) {
        return Result.failure(ApiErrorCode.CONFLICT, exception.getMessage());
    }

    /** 暂时性数据库锁竞争，管理员沿用原幂等键重试。 */
    @ExceptionHandler(org.springframework.dao.TransientDataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleStorageBusy(RuntimeException exception) {
        return Result.failure(ApiErrorCode.SERVICE_BUSY, "活动暂时处理中，请保持原参数重试");
    }

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 捕获未被专门处理的异常，记录完整错误日志并返回 HTTP 500。 响应使用通用提示，日志通过 traceId 关联具体错误。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception exception) {
        log.error("活动接口处理失败", exception);
        return Result.failure(ApiErrorCode.INTERNAL_ERROR, "服务暂时不可用，请稍后重试");
    }
}
