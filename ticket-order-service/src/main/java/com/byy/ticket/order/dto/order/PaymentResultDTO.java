package com.byy.ticket.order.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 通知只携带查询线索；金额、用户和成功时间必须回查支付服务，不能相信请求报文。 */
public record PaymentResultDTO(
        @NotBlank @Pattern(regexp="[0-9a-f]{32}") String orderNo,
        @NotBlank @Pattern(regexp="[0-9a-f]{32}") String paymentNo) { }
