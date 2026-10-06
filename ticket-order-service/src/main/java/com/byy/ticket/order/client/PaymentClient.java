package com.byy.ticket.order.client;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.order.client.dto.PaymentCreateRequest;
import com.byy.ticket.order.client.dto.PaymentResponse;
import com.byy.ticket.order.client.dto.PaymentReversalRequest;
import com.byy.ticket.order.client.dto.PaymentReversalResponse;
import com.byy.ticket.order.client.exception.PaymentServiceCallException;
import com.byy.ticket.order.client.exception.PaymentServiceCallException.Reason;
import com.byy.ticket.order.config.PaymentClientProperties;
import com.byy.ticket.security.service.OrderPaymentCredential;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** 订单到支付的HTTP创建调用；按服务名发现实例、传播trace、检查响应，不自动重试写请求。 */
@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.byy.ticket.resilience.HttpResilienceProperties.class)
public class PaymentClient {
    private final com.byy.ticket.resilience.HttpCallProtection protection;

    /** 查询、创建和冲正共享支付下游保护；不自动重试写请求。 */
    public PaymentReversalResponse reverse(PaymentReversalRequest request,String orderNo,BigDecimal amount) {
        if(request==null || !validNo(request.paymentNo()) || !validNo(orderNo) || !validAmount(amount)
                || request.reason()==null || request.reason().isBlank() || request.reason().length()>256)
            throw new IllegalArgumentException("冲正快照参数不正确");
        return protection.execute(() -> reverseHttp(request,orderNo,amount));
    }

    public PaymentResponse getByOrder(String orderNo) {
        if(!validNo(orderNo))throw new IllegalArgumentException("订单编号不正确");
        return protection.execute(() -> getByOrderHttp(orderNo));
    }

    public PaymentResponse createPayment(PaymentCreateRequest request) {
        validateRequest(request);
        return protection.execute(() -> createPaymentHttp(request));
    }

    /** 本地诊断和验证读取保护状态。 */
    public com.byy.ticket.resilience.HttpCallProtection protection() { return protection; }
    /** 幂等全额冲正；核对原付款、订单、金额和首次原因，不自动重复发送 POST。 */
    private PaymentReversalResponse reverseHttp(PaymentReversalRequest request, String orderNo, BigDecimal amount) {
        if (request == null || !validNo(request.paymentNo()) || !validNo(orderNo) || !validAmount(amount)
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 256) {
            throw new IllegalArgumentException("冲正快照参数不正确");
        }
        try {
            return restClient.post().uri("/internal/payment-reversals")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .header(TraceIdContext.HTTP_HEADER, TraceIdContext.getOrCreate()).body(request)
                    .exchange((sent, received) -> {
                        int status = received.getStatusCode().value();
                        if (status == 409) {
                            Result<Object> error = received.bodyTo(ERROR_TYPE);
                            if (error == null || !"CONFLICT".equals(error.code())) { throw invalidResponse("冲正冲突响应异常"); }
                            throw new PaymentServiceCallException(Reason.CONFLICT, "支付冲正参数或状态冲突，需核对");
                        }
                        // 默认模拟开关关闭时也会返回404；保留待冲正状态，待配置恢复后重试。
                        if(status==404)throw PaymentServiceCallException.rejectedResponse(Reason.UNAVAILABLE,"模拟冲正入口未开启，请保留待冲正进度");
                        if (received.getStatusCode().is5xxServerError()) {
                            throw new PaymentServiceCallException(Reason.UNAVAILABLE, "冲正服务暂时不可用或模拟开关未开启");
                        }
                        if (!received.getStatusCode().is2xxSuccessful()) {
                            if(received.getStatusCode().is4xxClientError())throw PaymentServiceCallException.rejectedResponse(Reason.INVALID_RESPONSE,"冲正HTTP请求被拒绝");
                            throw invalidResponse("冲正HTTP状态异常");
                        }
                        var type = new ParameterizedTypeReference<Result<PaymentReversalResponse>>() { };
                        var result = received.bodyTo(type);
                        var value = result == null ? null : result.data();
                        if (result == null || !"OK".equals(result.code()) || value == null
                                || !validNo(value.reversalNo()) || !request.paymentNo().equals(value.paymentNo())
                                || !orderNo.equals(value.orderNo()) || value.amount() == null
                                || amount.compareTo(value.amount()) != 0 || !request.reason().equals(value.reason())
                                || !"SUCCESS".equals(value.status()) || !validTime(value.completedAt())
                                || !validTime(value.createdAt())) {
                            throw invalidResponse("冲正结果不完整或归属错误");
                        }
                        return value;
                    });
        } catch (ResourceAccessException exception) {
            throw new PaymentServiceCallException(isTimeout(exception) ? Reason.TIMEOUT : Reason.UNAVAILABLE,
                    "冲正调用失败，可能已执行，请使用原编号核对", exception);
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("No instances available for ")) {
                throw new PaymentServiceCallException(Reason.UNAVAILABLE, "支付服务没有可用实例", exception);
            }
            throw exception;
        } catch (RestClientException | HttpMessageConversionException exception) {
            throw new PaymentServiceCallException(isTimeout(exception) ? Reason.TIMEOUT : Reason.INVALID_RESPONSE,
                    "冲正响应读取失败，可能已执行，请核对", exception);
        }
    }
    /** 根据原订单查询权威支付事实；通知中的成功标记和金额不能作为付款依据。 */
    private PaymentResponse getByOrderHttp(String orderNo) {
        if (!validNo(orderNo)) { throw new IllegalArgumentException("订单编号不正确"); }
        try {
            return restClient.get().uri("/internal/payments/by-order/{orderNo}", orderNo)
                    .header(TraceIdContext.HTTP_HEADER, TraceIdContext.getOrCreate())
                    .exchange((sent, received) -> {
                        int status = received.getStatusCode().value();
                        if (status == 404) {
                            throw new com.byy.ticket.common.exception.ResourceNotFoundException("支付单不存在");
                        }
                        if (received.getStatusCode().is5xxServerError()) {
                            throw new PaymentServiceCallException(Reason.UNAVAILABLE, "支付服务暂时不可用");
                        }
                        if (!received.getStatusCode().is2xxSuccessful()) {
                            if(received.getStatusCode().is4xxClientError())throw PaymentServiceCallException.rejectedResponse(Reason.INVALID_RESPONSE,"支付查询请求被拒绝");
                            throw invalidResponse("支付查询HTTP状态异常");
                        }
                        Result<PaymentResponse> result = received.bodyTo(RESPONSE_TYPE);
                        if (result == null || !"OK".equals(result.code()) || !validResponse(result.data())
                                || !orderNo.equals(result.data().orderNo())) {
                            throw invalidResponse("支付查询结果不完整或归属错误");
                        }
                        return result.data();
                    });
        } catch (ResourceAccessException exception) {
            throw new PaymentServiceCallException(isTimeout(exception) ? Reason.TIMEOUT : Reason.UNAVAILABLE,
                    "支付事实查询暂时失败", exception);
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("No instances available for ")) {
                throw new PaymentServiceCallException(Reason.UNAVAILABLE, "支付服务没有可用实例", exception);
            }
            throw exception;
        } catch (RestClientException | HttpMessageConversionException exception) {
            throw new PaymentServiceCallException(isTimeout(exception) ? Reason.TIMEOUT : Reason.INVALID_RESPONSE,
                    "支付事实响应读取失败", exception);
        }
    }
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");
    private static final ParameterizedTypeReference<Result<PaymentResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Result<Object>> ERROR_TYPE =
            new ParameterizedTypeReference<>() { };
    private final RestClient restClient;

    /** 注入支付专用Builder，服务名由LoadBalancer解析成实例地址。 */
    public PaymentClient(@LoadBalanced @Qualifier("paymentRestClientBuilder") RestClient.Builder builder,
                         PaymentClientProperties properties, OrderPaymentCredential credential) {
        this(builder,properties,credential,com.byy.ticket.resilience.HttpResilienceProperties.defaults());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentClient(@LoadBalanced @Qualifier("paymentRestClientBuilder") RestClient.Builder builder,
            PaymentClientProperties properties,OrderPaymentCredential credential,
            com.byy.ticket.resilience.HttpResilienceProperties settings) {
        this(builder,properties,credential,settings.payment());
    }

    /** 构建支付下游保护器；业务冲突排除，本地拒绝保留原请求与恢复进度。 */
    public PaymentClient(RestClient.Builder builder,PaymentClientProperties properties,OrderPaymentCredential credential,
            com.byy.ticket.resilience.HttpResilienceProperties.Policy policy) {
        // 三个内部调用都携带Order专用服务凭证，不转发用户Token充当服务身份。
        restClient = builder.clone().baseUrl("http://" + properties.serviceId())
                .defaultHeader(OrderPaymentCredential.HEADER, credential.value()).build();
        protection=new com.byy.ticket.resilience.HttpCallProtection("order-payment",policy,
                error -> error instanceof PaymentServiceCallException remote && remote.getReason()!=Reason.CONFLICT
                        && remote.isCircuitBreakerFailure(),
                denied -> new PaymentServiceCallException(Reason.UNAVAILABLE,"支付调用暂被保护组件拒绝，尚未发送请求，请保留原编号",denied));
    }

    /** 创建或取回同一订单的支付单；核对用户、金额和期限，未知结果只能用原参数核对或重试。 */
    private PaymentResponse createPaymentHttp(PaymentCreateRequest request) {
        validateRequest(request);
        try {
            return restClient.post().uri("/internal/payments")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .header(TraceIdContext.HTTP_HEADER, TraceIdContext.getOrCreate())
                    .body(request)
                    .exchange((sent, received) -> {
                        int status = received.getStatusCode().value();
                        if (received.getStatusCode().is5xxServerError()) {
                            throw new PaymentServiceCallException(Reason.UNAVAILABLE,
                                    "支付服务暂时不可用，支付单可能已创建，请使用原订单编号重试");
                        }
                        if (status == 400 || status == 409) {
                            Result<Object> error = received.bodyTo(ERROR_TYPE);
                            String expected = status == 400 ? "BAD_REQUEST" : "CONFLICT";
                            if (error == null || !expected.equals(error.code())) {
                                throw invalidResponse("支付服务错误响应不符合约定");
                            }
                            if (status == 400) {
                                throw new IllegalArgumentException("支付服务拒绝了订单支付参数");
                            }
                            throw new PaymentServiceCallException(Reason.CONFLICT,
                                    "支付单已到期或同一订单的支付参数不一致");
                        }
                        // 创建接口没有合法404业务结果；重定向和其他4xx均按契约错误处理。
                        if (!received.getStatusCode().is2xxSuccessful()) {
                            if(received.getStatusCode().is4xxClientError())throw PaymentServiceCallException.rejectedResponse(Reason.INVALID_RESPONSE,"支付创建请求被拒绝");
                            throw invalidResponse("支付服务HTTP状态不符合预期");
                        }
                        Result<PaymentResponse> result = received.bodyTo(RESPONSE_TYPE);
                        if (result == null || !"OK".equals(result.code())
                                || !validResponse(result.data()) || !matches(result.data(), request)) {
                            throw invalidResponse("支付单响应不完整或与原订单快照不一致");
                        }
                        return result.data();
                    });
        } catch (ResourceAccessException exception) {
            boolean timeout = isTimeout(exception);
            throw new PaymentServiceCallException(timeout ? Reason.TIMEOUT : Reason.UNAVAILABLE,
                    timeout ? "支付服务调用超时，支付单可能已创建，请使用原订单编号重试"
                            : "支付服务连接失败，请使用原订单编号核对或重试", exception);
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("No instances available for ")) {
                throw new PaymentServiceCallException(Reason.UNAVAILABLE, "支付服务没有可用实例", exception);
            }
            throw exception;
        } catch (RestClientException | HttpMessageConversionException exception) {
            if (isTimeout(exception)) {
                throw new PaymentServiceCallException(Reason.TIMEOUT,
                        "支付响应读取超时，支付单可能已创建，请使用原订单编号重试", exception);
            }
            throw new PaymentServiceCallException(Reason.INVALID_RESPONSE,
                    "支付服务响应解析失败，请使用原订单编号核对", exception);
        }
    }

    /** 校验订单侧请求，不要求期限仍在未来，让底层Client保留过期幂等核对能力。 */
    private void validateRequest(PaymentCreateRequest request) {
        if (request == null || !validNo(request.orderNo()) || request.userId() == null || request.userId() < 1
                || !validAmount(request.amount()) || !validTime(request.expiresAt())) {
            throw new IllegalArgumentException("订单支付快照参数不正确");
        }
    }

    /** 检查支付事实的状态与时间组合，避免把不完整或矛盾的结果返回用户。 */
    private boolean validResponse(PaymentResponse response) {
        if (response == null || !validNo(response.paymentNo()) || !validNo(response.orderNo())
                || response.userId() == null || response.userId() < 1 || !validAmount(response.amount())
                || !validTime(response.expiresAt()) || !validTime(response.createdAt())) {
            return false;
        }
        if ("CREATED".equals(response.status())) {
            return response.paidAt() == null && "NONE".equals(response.notifyStatus())
                    && response.reversalNo() == null && response.reversalStatus() == null;
        }
        if (!"SUCCESS".equals(response.status()) || !validTime(response.paidAt())
                || !response.paidAt().isBefore(response.expiresAt())
                || !Set.of("PENDING", "DELIVERED").contains(response.notifyStatus() == null ? "" : response.notifyStatus())) {
            return false;
        }
        return response.reversalNo() == null && response.reversalStatus() == null
                || validNo(response.reversalNo())
                && Set.of("PENDING", "SUCCESS").contains(response.reversalStatus() == null ? "" : response.reversalStatus());
    }

    /** 确认创建响应属于同一订单、用户、金额和原期限，金额按数值比较。 */
    private boolean matches(PaymentResponse response, PaymentCreateRequest request) {
        return request.orderNo().equals(response.orderNo()) && request.userId().equals(response.userId())
                && request.amount().compareTo(response.amount()) == 0
                && request.expiresAt().equals(response.expiresAt());
    }

    /** 编号采用32位小写十六进制。 */
    private boolean validNo(String value) { return value != null && value.matches("[0-9a-f]{32}"); }

    /** 金额必须能无损保存至支付库。 */
    private boolean validAmount(BigDecimal value) {
        return value != null && value.signum() > 0 && value.scale() <= 2 && value.compareTo(MAX_AMOUNT) <= 0;
    }

    /** 时间必须在MySQL范围且精度不超过毫秒，与跨服务数据库快照一致。 */
    private boolean validTime(LocalDateTime value) {
        return value != null && value.getYear() >= 1000 && value.getYear() <= 9999
                && value.getNano() % 1_000_000 == 0;
    }

    /** 向外保留响应契约错误分类。 */
    private PaymentServiceCallException invalidResponse(String message) {
        return new PaymentServiceCallException(Reason.INVALID_RESPONSE, message);
    }

    /** 沿cause链识别连接或正文转换器包装的超时。 */
    private boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) { return true; }
        }
        return false;
    }
}
