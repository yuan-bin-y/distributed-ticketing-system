package com.byy.ticket.order.client;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.order.client.dto.TicketPurchaseRuleResponse;
import com.byy.ticket.order.client.exception.EventServiceCallException;
import com.byy.ticket.order.client.exception.EventServiceCallException.Reason;
import com.byy.ticket.order.config.EventClientProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/** 封装 Order 到 Event 的远程调用，业务层只接收购票规则。 */
@Component
public class EventClient {
    private static final ParameterizedTypeReference<Result<TicketPurchaseRuleResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private final RestClient restClient;

    public EventClient(@LoadBalanced RestClient.Builder builder, EventClientProperties properties) {
        this.restClient = builder.clone().baseUrl("http://" + properties.serviceId()).build();
    }

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
                        throw new EventServiceCallException(Reason.INVALID_RESPONSE, "活动服务响应不符合预期");
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
            throw new EventServiceCallException(Reason.INVALID_RESPONSE, "活动服务响应解析失败", exception);
        }
    }

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
