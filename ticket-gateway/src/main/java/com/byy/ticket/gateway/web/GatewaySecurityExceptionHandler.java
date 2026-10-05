package com.byy.ticket.gateway.web;

import com.byy.ticket.security.session.SessionStoreUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** Redis故障返回503，优先于默认异常处理；凭证无效由Security返回401。 */
@Component
@Order(-2)
public class GatewaySecurityExceptionHandler implements WebExceptionHandler {
    private final GatewaySecurityResponses responses;
    /** 复用统一JSON错误响应。 */
    public GatewaySecurityExceptionHandler(GatewaySecurityResponses responses) { this.responses = responses; }
    /** 即使故障被上层包装，也识别会话存储不可用；其他异常交给后续处理器。 */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SessionStoreUnavailableException && !exchange.getResponse().isCommitted()) {
                return responses.write(exchange,HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_BUSY","登录会话存储暂时不可用");
            }
            current = current.getCause();
        }
        return Mono.error(exception);
    }
}
