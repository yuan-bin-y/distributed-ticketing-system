package com.byy.ticket.order.web.handler;

import com.byy.ticket.common.result.ApiErrorCode;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.order.client.exception.EventServiceCallException;
import com.byy.ticket.order.client.exception.InventoryServiceCallException;
import com.byy.ticket.order.client.exception.PaymentServiceCallException;
import com.byy.ticket.order.exception.OrderConflictException;
import com.byy.ticket.order.exception.OrderIdentityException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一处理订单服务 Controller 和业务层抛出的异常，生成约定的 HTTP 状态与 Result 响应。 业务代码负责抛出异常，Controller 不必逐个捕获并拼装错误 JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler
        extends com.byy.ticket.observability.web.ServletExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 没有可信身份时返回 401；开发身份必须显式开启，不能默认冒用用户编号。 */
    @ExceptionHandler(OrderIdentityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleIdentity(OrderIdentityException exception) {
        return Result.failure(ApiErrorCode.UNAUTHORIZED, exception.getMessage());
    }

    /** 购买幂等参数、累计限购或订单状态冲突返回409。 */
    @ExceptionHandler(OrderConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleOrderConflict(OrderConflictException exception) {
        return Result.failure(ApiErrorCode.CONFLICT, exception.getMessage());
    }

    /** 暂时性数据库失败返回 503，客户端应保持原购买幂等键重试。 */
    @ExceptionHandler(TransientDataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleStorageBusy(TransientDataAccessException exception) {
        log.warn("订单数据库暂时不可用", exception);
        return Result.failure(ApiErrorCode.SERVICE_BUSY, "订单处理中，请保持原购买幂等键核对或重试");
    }

    /** 根据活动调用失败类别，分别返回不可用 503、超时 504 或无效响应 502，并记录告警日志。 */
    @ExceptionHandler(EventServiceCallException.class)
    public ResponseEntity<Result<Void>> handleRemoteFailure(EventServiceCallException exception) {
        log.warn("活动服务调用失败，原因={}", exception.getReason(), exception);
        return switch (exception.getReason()) {
            case UNAVAILABLE ->
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(
                                    Result.failure(
                                            ApiErrorCode.UPSTREAM_UNAVAILABLE,
                                            exception.getMessage()));
            case TIMEOUT ->
                    ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                            .body(
                                    Result.failure(
                                            ApiErrorCode.UPSTREAM_TIMEOUT, exception.getMessage()));
            case INVALID_RESPONSE ->
                    ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(
                                    Result.failure(
                                            ApiErrorCode.INVALID_UPSTREAM_RESPONSE,
                                            exception.getMessage()));
        };
    }

    /** 库存业务冲突返回 409；不可用、超时或无效响应分别返回 503、504、502。 网络和响应异常记录告警，业务冲突按正常失败结果返回。 */
    @ExceptionHandler(InventoryServiceCallException.class)
    public ResponseEntity<Result<Void>> handleInventoryFailure(
            InventoryServiceCallException exception) {
        if (exception.getReason() != InventoryServiceCallException.Reason.CONFLICT) {
            log.warn("库存服务调用失败，原因={}", exception.getReason(), exception);
        }
        return switch (exception.getReason()) {
            case CONFLICT ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Result.failure(ApiErrorCode.CONFLICT, exception.getMessage()));
            case UNAVAILABLE ->
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(
                                    Result.failure(
                                            ApiErrorCode.UPSTREAM_UNAVAILABLE,
                                            exception.getMessage()));
            case TIMEOUT ->
                    ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                            .body(
                                    Result.failure(
                                            ApiErrorCode.UPSTREAM_TIMEOUT, exception.getMessage()));
            case INVALID_RESPONSE ->
                    ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(
                                    Result.failure(
                                            ApiErrorCode.INVALID_UPSTREAM_RESPONSE,
                                            exception.getMessage()));
        };
    }

    /** 支付业务冲突返回409；网络不可用、超时和响应错误分别返回503、504、502。 */
    @ExceptionHandler(PaymentServiceCallException.class)
    public ResponseEntity<Result<Void>> handlePaymentFailure(
            PaymentServiceCallException exception) {
        if (exception.getReason() != PaymentServiceCallException.Reason.CONFLICT) {
            log.warn("支付服务调用失败，原因={}", exception.getReason(), exception);
        }
        return switch (exception.getReason()) {
            case CONFLICT ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Result.failure(ApiErrorCode.CONFLICT, exception.getMessage()));
            case UNAVAILABLE ->
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(
                                    Result.failure(
                                            ApiErrorCode.UPSTREAM_UNAVAILABLE,
                                            exception.getMessage()));
            case TIMEOUT ->
                    ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                            .body(
                                    Result.failure(
                                            ApiErrorCode.UPSTREAM_TIMEOUT, exception.getMessage()));
            case INVALID_RESPONSE ->
                    ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(
                                    Result.failure(
                                            ApiErrorCode.INVALID_UPSTREAM_RESPONSE,
                                            exception.getMessage()));
        };
    }

    /** 捕获未被专门处理的异常，记录完整错误日志并返回 HTTP 500。 响应使用通用提示，日志通过 traceId 关联具体错误。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception exception) {
        log.error("订单接口处理失败", exception);
        return Result.failure(ApiErrorCode.INTERNAL_ERROR, "服务暂时不可用，请稍后重试");
    }
}
