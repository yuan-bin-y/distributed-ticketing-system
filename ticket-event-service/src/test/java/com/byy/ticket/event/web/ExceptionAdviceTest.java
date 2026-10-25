package com.byy.ticket.event.web;

import com.byy.ticket.event.web.handler.GlobalExceptionHandler;
import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.observability.web.ServletExceptionAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import static org.junit.jupiter.api.Assertions.*;

/** 验证实际业务Advice可初始化，防止继承后出现重复异常映射。 */
class ExceptionAdviceTest {
    @Test void inheritedMappingsAreUnambiguousAndKeepSpecificBusinessHandlers() {
        var resolver = assertDoesNotThrow(() -> new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class));
        assertEquals(ServletExceptionAdvice.class, resolver.resolveMethod(new ResourceNotFoundException("missing")).getDeclaringClass());
        assertEquals(ServletExceptionAdvice.class, resolver.resolveMethod(new IllegalArgumentException("bad")).getDeclaringClass());
        assertEquals(GlobalExceptionHandler.class, resolver.resolveMethod(new IllegalStateException("unexpected")).getDeclaringClass());
    }
}
