package com.byy.ticket.inventory.web.handler;

import com.byy.ticket.common.result.ApiErrorCode;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.inventory.exception.InventoryConflictException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一处理库存服务 Controller 和业务层抛出的异常，生成约定的 HTTP 状态与 Result 响应。 业务代码负责抛出异常，Controller 不必逐个捕获并拼装错误 JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler
        extends com.byy.ticket.observability.web.ServletExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 容量满额属于正常削峰；不输出堆栈，客户端不得据此熔断整个库存服务。 */
    @ExceptionHandler(com.byy.ticket.inventory.exception.HotStockBusyException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleHotStockBusy(
            com.byy.ticket.inventory.exception.HotStockBusyException exception) {
        return new Result<>(
                "STOCK_BUSY",
                exception.getMessage(),
                null,
                com.byy.ticket.common.trace.TraceIdContext.getOrCreate());
    }

    /** 库存不足、重复订单参数冲突或非法状态转换时，返回 HTTP 409。 */
    @ExceptionHandler(InventoryConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleConflict(InventoryConflictException exception) {
        return Result.failure(ApiErrorCode.CONFLICT, exception.getMessage());
    }

    /** 数据库锁竞争等暂时性访问失败时，返回 HTTP 503 和 SERVICE_BUSY。 提示调用方稍后使用同一订单编号和相同参数重试，避免创建新的预留。 */
    @ExceptionHandler(TransientDataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleStorageBusy(TransientDataAccessException exception) {
        log.warn("库存事务遇到数据库锁竞争或临时故障", exception);
        return Result.failure(ApiErrorCode.SERVICE_BUSY, "库存服务繁忙，请使用相同订单编号和参数重试");
    }

    /** 捕获未被专门处理的异常，记录完整错误日志并返回 HTTP 500。 响应使用通用提示，日志通过 traceId 关联具体错误。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception exception) {
        log.error("库存接口处理失败", exception);
        return Result.failure(ApiErrorCode.INTERNAL_ERROR, "服务暂时不可用，请稍后重试");
    }
}
