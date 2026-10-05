package com.byy.ticket.gateway.web;

import com.byy.ticket.common.result.Result;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** 响应式安全错误写入；traceId从exchange获取，不使用Servlet线程MDC。 */
@Component
public class GatewaySecurityResponses {
    private final ObjectMapper json;
    /** 注入网关JSON编码器。 */
    public GatewaySecurityResponses(ObjectMapper json) { this.json = json; }
    /** 写入统一Result；不输出凭证、Redis键或底层故障详情。 */
    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        return Mono.defer(() -> {
            var response = exchange.getResponse();
            response.setStatusCode(status); response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] body = json.writeValueAsBytes(new Result<Void>(code,message,null,exchange.getAttribute(GatewayTraceFilter.ATTRIBUTE)));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        });
    }
}
