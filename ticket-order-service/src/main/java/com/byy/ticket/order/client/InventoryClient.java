package com.byy.ticket.order.client;

import com.byy.ticket.common.exception.ResourceNotFoundException;
import com.byy.ticket.common.result.Result;
import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.order.client.dto.StockReservationRequest;
import com.byy.ticket.order.client.dto.StockReservationResponse;
import com.byy.ticket.order.client.exception.InventoryServiceCallException;
import com.byy.ticket.order.client.exception.InventoryServiceCallException.Reason;
import com.byy.ticket.order.config.InventoryClientProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import com.byy.ticket.security.service.OrderInventoryCredential;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * 封装订单到库存服务的预留、确认、释放和查询 HTTP 调用。
 * 每次调用核对统一响应和业务字段；不自动重试写请求，避免丢失响应时误判库存状态。
 */
@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.byy.ticket.resilience.HttpResilienceProperties.class)
public class InventoryClient {
    private static final ParameterizedTypeReference<Result<StockReservationResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Result<Object>> ERROR_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final Set<String> STATUSES = Set.of("RESERVED", "SOLD", "RELEASED");
    private final RestClient restClient;
    private final com.byy.ticket.resilience.HttpCallProtection protection;

    /**
     * 使用库存专用、带负载均衡的 Builder，复制后把库存服务名设置成 baseUrl。
     */
    public InventoryClient(@LoadBalanced @Qualifier("inventoryRestClientBuilder") RestClient.Builder builder,
                           InventoryClientProperties properties, OrderInventoryCredential credential) {
        this(builder,properties,credential,com.byy.ticket.resilience.HttpResilienceProperties.defaults());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InventoryClient(@LoadBalanced @Qualifier("inventoryRestClientBuilder") RestClient.Builder builder,
            InventoryClientProperties properties,OrderInventoryCredential credential,
            com.byy.ticket.resilience.HttpResilienceProperties settings) {
        this(builder,properties,credential,settings.inventory());
    }

    /** 构建库存专属保护器；明确业务冲突排除，拒绝交给原持久化恢复流程。 */
    public InventoryClient(RestClient.Builder builder,InventoryClientProperties properties,OrderInventoryCredential credential,
            com.byy.ticket.resilience.HttpResilienceProperties.Policy policy) {
        restClient = builder.clone().baseUrl("http://" + properties.serviceId())
                .defaultHeader(OrderInventoryCredential.HEADER, credential.value()).build();
        protection=new com.byy.ticket.resilience.HttpCallProtection("order-inventory",policy,
                error -> error instanceof InventoryServiceCallException remote && remote.getReason()!=Reason.CONFLICT
                        && remote.isCircuitBreakerFailure(),
                denied -> new InventoryServiceCallException(Reason.UNAVAILABLE,"库存调用暂被保护组件拒绝，尚未发送请求，请保留进度稍后核对",denied));
    }

    /**
     * POST /internal/stock-reservations：校验请求，发送 JSON，再核对返回的原请求参数。
     * 同一订单重试可能返回 RESERVED、SOLD 或 RELEASED，必须保留原状态，不当成新的预留。
     */
    public StockReservationResponse reserve(StockReservationRequest request) {
        validateRequest(request);
        return execute(restClient.post().uri("/internal/stock-reservations")
                        .contentType(MediaType.APPLICATION_JSON).body(request),
                response -> request.orderId().equals(response.orderId())
                        && request.sessionId().equals(response.sessionId())
                        && request.ticketTierId().equals(response.ticketTierId())
                        && request.quantity().equals(response.quantity())
                        && request.expiresAt().equals(response.expiresAt()));
    }

    /**
     * POST /internal/stock-reservations/{reservationId}/confirm：请求确认售出。
     * 成功响应必须是同一个预留编号且状态为 SOLD。
     */
    public StockReservationResponse confirm(String reservationId) {
        validateReservationId(reservationId);
        return execute(restClient.post().uri("/internal/stock-reservations/{reservationId}/confirm", reservationId),
                response -> reservationId.equals(response.reservationId()) && "SOLD".equals(response.status()));
    }

    /**
     * POST /internal/stock-reservations/{reservationId}/release：请求释放预留。
     * 成功响应必须是同一个预留编号且状态为 RELEASED。
     */
    public StockReservationResponse release(String reservationId) {
        validateReservationId(reservationId);
        return execute(restClient.post().uri("/internal/stock-reservations/{reservationId}/release", reservationId),
                response -> reservationId.equals(response.reservationId()) && "RELEASED".equals(response.status()));
    }

    /**
     * GET /internal/stock-reservations/{reservationId}：查询已知预留，核对编号和状态。
     * 请求超时不等于库存操作失败，可根据已知编号核对结果；此方法不修改库存。
     */
    public StockReservationResponse getReservation(String reservationId) {
        validateReservationId(reservationId);
        return execute(restClient.get().uri("/internal/stock-reservations/{reservationId}", reservationId),
                response -> reservationId.equals(response.reservationId()));
    }

    /**
     * 统一执行 HTTP 请求：携带 traceId、接收 JSON，并核对状态码、错误码和响应字段。
     * 400/404/409 必须匹配预期业务码，其他异常状态分别按不可用或无效响应处理。
     * 连接或读取超时单独转换；写请求不自动重试，响应丢失时库存事务可能已经提交。
     */
    private StockReservationResponse execute(RestClient.RequestHeadersSpec<?> request,
                                               Predicate<StockReservationResponse> matches) {
        return protection.execute(() -> executeHttp(request,matches));
    }

    /** 实际HTTP与异常归一化处于熔断统计内，保留请求结果未知的语义。 */
    private StockReservationResponse executeHttp(RestClient.RequestHeadersSpec<?> request,
                                               Predicate<StockReservationResponse> matches) {
        try {
            return request.header(TraceIdContext.HTTP_HEADER, TraceIdContext.getOrCreate())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((sent, received) -> {
                        int status = received.getStatusCode().value();
                        if(status==503) {
                            Result<Object> error=received.bodyTo(ERROR_TYPE);
                            if(error!=null && "STOCK_BUSY".equals(error.code()))
                                throw InventoryServiceCallException.capacityRejected();
                            throw new InventoryServiceCallException(Reason.UNAVAILABLE,
                                    "库存服务暂时不可用，操作结果请使用相同请求核对或重试");
                        }
                        if (received.getStatusCode().is5xxServerError()) {
                            throw new InventoryServiceCallException(Reason.UNAVAILABLE,
                                    "库存服务暂时不可用，操作结果请使用相同请求核对或重试");
                        }
                        if (status == 400 || status == 404 || status == 409) {
                            Result<Object> error = received.bodyTo(ERROR_TYPE);
                            String expectedCode = status == 400 ? "BAD_REQUEST"
                                    : status == 404 ? "RESOURCE_NOT_FOUND" : "CONFLICT";
                            if (error == null || !expectedCode.equals(error.code())) {
                                throw invalidResponse("库存服务错误响应不符合预期");
                            }
                            if (status == 400) {
                                throw new IllegalArgumentException("库存服务拒绝了请求参数，请检查预留参数");
                            }
                            if (status == 404) {
                                throw new ResourceNotFoundException("库存或预留记录不存在");
                            }
                            throw new InventoryServiceCallException(Reason.CONFLICT,
                                    "库存不足、预留参数不一致或预留状态冲突");
                        }
                        // exchange 自行处理 HTTP 状态；3xx 和其他4xx均不能当作成功。
                        if (!received.getStatusCode().is2xxSuccessful()) {
                            if(received.getStatusCode().is4xxClientError())
                                throw InventoryServiceCallException.rejectedResponse("库存服务拒绝了请求");
                            throw invalidResponse("库存服务 HTTP 状态不符合预期");
                        }
                        Result<StockReservationResponse> result = received.bodyTo(RESPONSE_TYPE);
                        if (result == null || !"OK".equals(result.code()) || !isValidResponse(result.data())
                                || !matches.test(result.data())) {
                            throw invalidResponse("库存服务返回的预留结果不完整或与请求不一致");
                        }
                        return result.data();
                    });
        } catch (ResourceAccessException exception) {
            boolean timeout = isTimeout(exception);
            throw new InventoryServiceCallException(timeout ? Reason.TIMEOUT : Reason.UNAVAILABLE,
                    timeout ? "库存服务调用超时，操作可能已生效，请使用相同请求核对或重试"
                            : "库存服务连接失败，操作结果请使用相同请求核对或重试", exception);
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("No instances available for ")) {
                throw new InventoryServiceCallException(Reason.UNAVAILABLE, "库存服务没有可用实例，请稍后重试", exception);
            }
            throw exception;
        } catch (RestClientException | HttpMessageConversionException exception) {
            // 正文读取超时可能被 JSON 转换器包装，仍需保留 504 超时语义。
            if (isTimeout(exception)) {
                throw new InventoryServiceCallException(Reason.TIMEOUT,
                        "库存服务正文读取超时，操作可能已生效，请使用相同请求核对或重试", exception);
            }
            throw new InventoryServiceCallException(Reason.INVALID_RESPONSE,
                    "库存服务响应解析失败，操作结果请使用相同请求核对", exception);
        }
    }

    /** 本地验证读取状态；无公开管理接口。 */
    public com.byy.ticket.resilience.HttpCallProtection protection() { return protection; }

    /**
     * 验证预留编号、订单编号、各 ID、数量、日期及状态属于约定范围。
     * 请求与响应是否相符再由各调用方法传入的 matches 判定。
     */
    private boolean isValidResponse(StockReservationResponse response) {
        return response != null && response.reservationId() != null
                && response.reservationId().matches("[0-9a-f]{32}")
                && response.orderId() != null && response.orderId().matches("[A-Za-z0-9_-]{1,64}")
                && response.sessionId() != null && response.sessionId() > 0
                && response.ticketTierId() != null && response.ticketTierId() > 0
                && response.quantity() != null && response.quantity() > 0
                && isValidExpiry(response.expiresAt())
                && response.status() != null && STATUSES.contains(response.status());
    }

    /**
     * 校验预留请求的编号、ID、数量以及日期范围和毫秒精度。
     * 不要求到期时间仍在未来，避免阻止已有预留的幂等重试。
     */
    private void validateRequest(StockReservationRequest request) {
        if (request == null || request.orderId() == null || !request.orderId().matches("[A-Za-z0-9_-]{1,64}")
                || request.sessionId() == null || request.sessionId() < 1
                || request.ticketTierId() == null || request.ticketTierId() < 1
                || request.quantity() == null || request.quantity() < 1 || !isValidExpiry(request.expiresAt())) {
            throw new IllegalArgumentException("库存预留参数不正确，时间精度最多毫秒");
        }
        // 不检查当前时间：原预留到期后仍应允许相同请求核对与重试。
    }

    /**
     * 检查日期能否按数据库 DATETIME(3) 保存：年份 1000 到 9999，精度不超过毫秒。
     */
    private boolean isValidExpiry(LocalDateTime expiry) {
        return expiry != null && expiry.getYear() >= 1000 && expiry.getYear() <= 9999
                && expiry.getNano() % 1_000_000 == 0;
    }

    /**
     * 要求预留编号是 32 位小写十六进制字符串，阻止不符合接口契约的请求。
     */
    private void validateReservationId(String reservationId) {
        if (reservationId == null || !reservationId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("预留ID须为32位小写十六进制字符");
        }
    }

    /**
     * 创建上游响应不符合契约的异常，由订单统一异常处理器转换为 HTTP 502。
     */
    private InventoryServiceCallException invalidResponse(String message) {
        return new InventoryServiceCallException(Reason.INVALID_RESPONSE, message);
    }

    /**
     * 沿异常 cause 链识别超时，兼容网络访问和 JSON 读取过程中包装的异常。
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
