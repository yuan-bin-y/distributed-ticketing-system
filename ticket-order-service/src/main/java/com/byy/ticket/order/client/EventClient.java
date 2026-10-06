package com.byy.ticket.order.client;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.order.client.dto.TicketPurchaseRuleResponse;
import com.byy.ticket.order.client.exception.EventServiceCallException;
import com.byy.ticket.order.client.exception.EventServiceCallException.Reason;
import com.byy.ticket.order.config.EventClientProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import com.byy.ticket.security.service.OrderEventCredential;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 封装订单到活动服务的 HTTP 调用：发现服务实例、发送请求、解析 JSON 并校验响应。
 * 业务层调用此组件的方法，不会直接调用另一个进程中的 EventServiceImpl。
 */
@Component
public class EventClient {
    private static final ParameterizedTypeReference<Result<TicketPurchaseRuleResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private final RestClient restClient;

    /**
     * 使用活动专用、带负载均衡的 Builder，复制后设置服务名为 baseUrl。
     * 真正请求时由 LoadBalancer 将服务名解析成可用实例地址；clone 避免修改共享 Builder。
     */
    public EventClient(@LoadBalanced @Qualifier("eventRestClientBuilder") RestClient.Builder builder,
                       EventClientProperties properties, OrderEventCredential credential) {
        this.restClient = builder.clone().baseUrl("http://" + properties.serviceId())
                .defaultHeader(OrderEventCredential.HEADER, credential.value()).build();
    }

    /**
     * GET /internal/ticket-tiers/{ticketTierId}/purchase-rule：携带 traceId 查询购票规则。
     * 解析 Result 包装的 JSON，检查成功码和字段完整性，再只返回规则数据。
     * 将不存在、网络不可用、超时和响应解析失败转换成业务层可处理的异常。
     */
    public TicketPurchaseRuleResponse getPurchaseRule(Long ticketTierId) {
        if (ticketTierId == null || ticketTierId < 1) {
            throw new IllegalArgumentException("票档ID必须大于零");
        }
        try {
            Result<TicketPurchaseRuleResponse> response = restClient.get()
                    .uri("/internal/ticket-tiers/{ticketTierId}/purchase-rule", ticketTierId)
                    .header(TraceIdContext.HTTP_HEADER, TraceIdContext.getOrCreate())
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, result) -> {
                        throw new ResourceNotFoundException("票档不存在或当前不可购买");
                    })
                    .onStatus(status -> status.is5xxServerError(), (request, result) -> {
                        throw new EventServiceCallException(Reason.UNAVAILABLE, "活动服务暂时不可用");
                    })
                    .onStatus(status -> status.is4xxClientError(), (request, result) -> {
                        throw EventServiceCallException.rejectedResponse("活动服务拒绝了购票规则查询");
                    })
                    .body(RESPONSE_TYPE);

            if (response == null || !"OK".equals(response.code()) || !isValidRule(response.data(), ticketTierId)) {
                throw new EventServiceCallException(Reason.INVALID_RESPONSE, "活动服务返回的购票规则不完整");
            }
            return response.data();
        } catch (ResourceAccessException exception) {
            boolean timeout = isTimeout(exception);
            throw new EventServiceCallException(timeout ? Reason.TIMEOUT : Reason.UNAVAILABLE,
                    timeout ? "活动服务调用超时，请稍后重试" : "活动服务暂时不可用", exception);
        } catch (IllegalStateException exception) {
            // BlockingLoadBalancerClient 在找不到服务实例时会抛出此异常。
            if (exception.getMessage() != null && exception.getMessage().startsWith("No instances available for ")) {
                throw new EventServiceCallException(Reason.UNAVAILABLE, "活动服务没有可用实例，请稍后重试", exception);
            }
            throw exception;
        } catch (RestClientException | HttpMessageConversionException exception) {
            if (isTimeout(exception)) {
                throw new EventServiceCallException(Reason.TIMEOUT, "活动服务调用超时，请稍后重试", exception);
            }
            throw new EventServiceCallException(Reason.INVALID_RESPONSE, "活动服务响应解析失败", exception);
        }
    }

    /**
     * 核对返回票档与请求一致，以及 ID、名称、价格、售票时间和限购数是否满足契约。
     * 此处验证响应结构，不判断当前是否已开售。
     */
    private boolean isValidRule(TicketPurchaseRuleResponse rule, Long requestedId) {
        return rule != null && requestedId.equals(rule.ticketTierId())
                && rule.eventId() != null && rule.eventId() > 0
                && rule.sessionId() != null && rule.sessionId() > 0
                && rule.ticketTierName() != null && !rule.ticketTierName().isBlank()
                && rule.price() != null && rule.price().signum() > 0
                && rule.saleStartTime() != null && rule.saleEndTime() != null
                && rule.saleEndTime().isAfter(rule.saleStartTime())
                && rule.purchaseLimit() != null && rule.purchaseLimit() > 0;
    }

    /**
     * 遍历异常及其 cause，判断是否包含 HTTP、Socket 或通用超时异常。
     */
    private boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
