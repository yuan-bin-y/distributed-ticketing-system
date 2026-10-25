package com.byy.ticket.observability.web;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.result.*;

import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.*;

/** MVC基础错误契约；各服务仅保留自己的业务、远程调用和最终异常处理。 */
public abstract class ServletExceptionAdvice {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Map<String, String>> handleValidation(MethodArgumentNotValidException error) {
        Map<String, String> fields = new LinkedHashMap<>();
        error.getBindingResult()
                .getFieldErrors()
                .forEach(
                        field ->
                                fields.putIfAbsent(
                                        field.getField(),
                                        field.isBindingFailure()
                                                ? "参数类型不正确"
                                                : field.getDefaultMessage()));
        return Result.validation(fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException error) {
        return Result.failure(ApiErrorCode.BAD_REQUEST, "参数类型不正确");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequest(IllegalArgumentException error) {
        return Result.failure(ApiErrorCode.BAD_REQUEST, error.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(ResourceNotFoundException error) {
        return Result.failure(ApiErrorCode.RESOURCE_NOT_FOUND, error.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException error) {
        return Result.failure(ApiErrorCode.RESOURCE_NOT_FOUND, "接口不存在");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethod(HttpRequestMethodNotSupportedException error) {
        return Result.failure(ApiErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持");
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleJson(RuntimeException error) {
        return Result.failure(ApiErrorCode.BAD_REQUEST, "请求正文格式不正确");
    }
}
