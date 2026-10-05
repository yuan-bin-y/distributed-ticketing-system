package com.byy.ticket.auth.web;

import com.byy.ticket.auth.exception.AuthException;
import com.byy.ticket.common.result.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** 认证异常统一返回状态码；不在响应和日志输出账号密码、完整请求或 Token。 */
@RestControllerAdvice
public class AuthExceptionHandler {
    /** 参数校验失败返回字段提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Result.validation(errors));
    }
    /** 登录、刷新失败和重复注册使用各自约定状态。 */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<?> auth(AuthException exception) {
        return ResponseEntity.status(exception.status()).body(Result.failure(exception.code(), exception.getMessage()));
    }
    /** 错误 JSON 或业务参数错误返回400。 */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<?> badRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Result.failure(ApiErrorCode.BAD_REQUEST, "参数不符合要求"));
    }
    /** MySQL/Redis 不可用时不能伪装登录成功，返回503便于用户重试。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable(DataAccessException exception) {
        return ResponseEntity.status(503).body(Result.failure(ApiErrorCode.SERVICE_BUSY, "认证存储暂时不可用"));
    }
    /** 未知错误不向客户端泄露内部信息。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception exception) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("认证处理失败，异常类型={}", exception.getClass().getName());
        return ResponseEntity.internalServerError().body(Result.failure(ApiErrorCode.INTERNAL_ERROR, "认证服务处理失败"));
    }
}
