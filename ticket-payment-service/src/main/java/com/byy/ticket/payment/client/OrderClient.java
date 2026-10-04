package com.byy.ticket.payment.client;

import com.byy.ticket.common.result.Result;
import com.byy.ticket.common.trace.TraceIdContext;
import com.byy.ticket.payment.config.PaymentNotificationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 支付到订单的 HTTP 通知；严格核对接收确认，HTTP 200 本身不能代表可靠接收。 */
@Component
public class OrderClient {
    private final RestClient http;
    private static final ParameterizedTypeReference<Result<Receipt>> TYPE = new ParameterizedTypeReference<>() { };

    /** 注入启用服务发现的独立 Builder。 */
    public OrderClient(@LoadBalanced @Qualifier("orderNotificationRestClientBuilder") RestClient.Builder builder,
                       PaymentNotificationProperties properties) {
        http = builder.clone().baseUrl("http://" + properties.serviceId()).build();
    }

    /** 发送固定订单和支付编号；错误、丢失响应或不匹配确认均交给后台保存重试进度。 */
    public void notifySuccess(String orderNo, String paymentNo) {
        http.post().uri("/internal/orders/payment-results")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .header(TraceIdContext.HTTP_HEADER, TraceIdContext.getOrCreate())
                .body(new Notification(orderNo, paymentNo))
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("订单通知HTTP失败，status=" + response.getStatusCode().value());
                    }
                    Result<Receipt> result = response.bodyTo(TYPE);
                    if (result == null || !"OK".equals(result.code()) || result.data() == null
                            || !result.data().accepted() || !orderNo.equals(result.data().orderNo())
                            || !paymentNo.equals(result.data().paymentNo())) {
                        throw new IllegalStateException("订单未返回匹配的可靠接收确认");
                    }
                    return Boolean.TRUE;
                });
    }

    /** 独立通知契约，不引用订单模块的业务类型。 */
    public record Notification(String orderNo, String paymentNo) { }
    /** accepted 只代表付款依据已落库，不代表订单成交。 */
    public record Receipt(String orderNo, String paymentNo, boolean accepted) { }
}
