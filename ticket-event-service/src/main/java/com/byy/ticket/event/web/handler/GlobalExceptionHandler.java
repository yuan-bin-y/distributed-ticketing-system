package com.byy.ticket.event.web.handler;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.result.ApiErrorCode;
import com.byy.ticket.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统一异常响应，同时保留正确的 HTTP 状态。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.isBindingFailure() ? "参数类型不正确" : error.getDefaultMessage()));
        return Result.validation(errors);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return Result.failure(ApiErrorCode.BAD_REQUEST, "参数类型不正确");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequest(IllegalArgumentException exception) {
        return Result.failure(ApiErrorCode.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(ResourceNotFoundException exception) {
        return Result.failure(ApiErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException exception) {
        return Result.failure(ApiErrorCode.RESOURCE_NOT_FOUND, "接口不存在");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException exception) {
        return Result.failure(ApiErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpected(Exception exception) {
        log.error("活动接口处理失败", exception);
        return Result.failure(ApiErrorCode.INTERNAL_ERROR, "服务暂时不可用，请稍后重试");
    }
}
