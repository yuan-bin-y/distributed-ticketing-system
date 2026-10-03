package com.byy.ticket.inventory.web.handler;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.result.ApiErrorCode;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.inventory.exception.InventoryConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一处理库存服务 Controller 和业务层抛出的异常，生成约定的 HTTP 状态与 Result 响应。
 * 业务代码负责抛出异常，Controller 不必逐个捕获并拼装错误 JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 将 Bean Validation 的字段错误转换为 HTTP 400，返回字段名和具体提示。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return Result.validation(errors);
    }

    /**
     * JSON 格式错误或路径参数类型错误时，返回 HTTP 400。
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleInvalidInput(Exception exception) {
        return Result.failure(ApiErrorCode.BAD_REQUEST, "请求体或参数类型不正确");
    }

    /**
     * 将业务参数校验抛出的 IllegalArgumentException 转换为 HTTP 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequest(IllegalArgumentException exception) {
        return Result.failure(ApiErrorCode.BAD_REQUEST, exception.getMessage());
    }

    /**
     * 将业务资源不存在异常转换为 HTTP 404，保留可读的业务提示。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(ResourceNotFoundException exception) {
        return Result.failure(ApiErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
    }

    /**
     * 库存不足、重复订单参数冲突或非法状态转换时，返回 HTTP 409。
     */
    @ExceptionHandler(InventoryConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> handleConflict(InventoryConflictException exception) {
        return Result.failure(ApiErrorCode.CONFLICT, exception.getMessage());
    }

    /**
     * 数据库锁竞争等暂时性访问失败时，返回 HTTP 503 和 SERVICE_BUSY。
     * 提示调用方稍后使用同一订单编号和相同参数重试，避免创建新的预留。
     */
    @ExceptionHandler(TransientDataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleStorageBusy(TransientDataAccessException exception) {
        log.warn("库存事务遇到数据库锁竞争或临时故障", exception);
        return Result.failure(ApiErrorCode.SERVICE_BUSY, "库存服务繁忙，请使用相同订单编号和参数重试");
    }

    /**
     * 请求路径没有匹配资源时返回 HTTP 404，提示接口不存在。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException exception) {
        return Result.failure(ApiErrorCode.RESOURCE_NOT_FOUND, "接口不存在");
    }

    /**
     * 请求路径存在但 HTTP 方法不支持时，返回 HTTP 405。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException exception) {
        return Result.failure(ApiErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持");
    }

    /**
     * 捕获未被专门处理的异常，记录完整错误日志并返回 HTTP 500。
     * 响应使用通用提示，日志通过 traceId 关联具体错误。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception exception) {
        log.error("库存接口处理失败", exception);
        return Result.failure(ApiErrorCode.INTERNAL_ERROR, "服务暂时不可用，请稍后重试");
    }
}
