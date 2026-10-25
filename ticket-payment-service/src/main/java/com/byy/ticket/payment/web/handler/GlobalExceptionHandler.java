package com.byy.ticket.payment.web.handler;

import com.byy.ticket.common.result.ApiErrorCode;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.payment.exception.PaymentConflictException;
import com.byy.ticket.payment.exception.PaymentIdentityException;

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
 * 统一处理支付服务 Controller 和业务层抛出的异常，生成约定的 HTTP 状态与 Result 响应。 业务代码负责抛出异常，Controller 不必逐个捕获并拼装错误 JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler
        extends com.byy.ticket.observability.web.ServletExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 公共接口缺少可信身份时返回 HTTP 401；开发身份头必须显式开启。 */
    @ExceptionHandler(PaymentIdentityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleIdentity(PaymentIdentityException exception) {
        return Result.failure(ApiErrorCode.UNAUTHORIZED, exception.getMessage());
    }

    /** 重复支付参数冲突、支付到期或非法冲正时，返回 HTTP 409。 */
    @ExceptionHandler(PaymentConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleConflict(PaymentConflictException exception) {
        return Result.failure(ApiErrorCode.CONFLICT, exception.getMessage());
    }

    /** 数据库锁竞争等暂时性访问失败时，返回 HTTP 503 和 SERVICE_BUSY。 提示调用方稍后使用同一订单编号和相同参数重试，避免创建新的支付单。 */
    @ExceptionHandler(TransientDataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleStorageBusy(TransientDataAccessException exception) {
        log.warn("支付事务遇到数据库锁竞争或临时故障", exception);
        return Result.failure(ApiErrorCode.SERVICE_BUSY, "支付服务繁忙，请使用相同订单编号和参数重试");
    }

    /** 捕获未被专门处理的异常，记录完整错误日志并返回 HTTP 500。 响应使用通用提示，日志通过 traceId 关联具体错误。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception exception) {
        log.error("支付接口处理失败", exception);
        return Result.failure(ApiErrorCode.INTERNAL_ERROR, "服务暂时不可用，请稍后重试");
    }
}
