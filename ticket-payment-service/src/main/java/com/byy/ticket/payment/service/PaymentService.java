package com.byy.ticket.payment.service;

import com.byy.ticket.payment.dto.payment.*;
import com.byy.ticket.payment.vo.payment.*;

/** 支付服务的独立业务能力；业务层只访问支付库，本阶段不调用其他服务。 */
public interface PaymentService {
    /** 同一订单相同参数只创建一张支付单，重复请求返回原记录。 */
    PaymentVO create(PaymentCreateDTO request);
    /** 为当前用户的支付单模拟付款成功；到期后首次付款拒绝，成功重试保持原结果。 */
    PaymentVO simulateSuccess(Long userId, String paymentNo);
    /** 按订单编号查询支付事实，供后续订单服务主动核对。 */
    PaymentVO getByOrder(String orderNo);
    /** 查询当前用户自己的支付单及冲正状态。 */
    PaymentVO getOwned(Long userId, String paymentNo);
    /** 对已付款的支付单模拟全额冲正，金额来自数据库，重复请求不重复执行。 */
    PaymentReversalVO reverse(PaymentReversalDTO request);
}
