// 从订单发起支付接口后置脚本；支付模拟成功响应使用status字段而非paymentStatus。
const result = pm.response.json();
if (pm.response.code !== 200 || result.code !== 'OK' || !result.data || !result.data.paymentNo) {
    throw new Error('创建支付单失败，不覆盖原支付编号');
}
pm.environment.set('payment_no', result.data.paymentNo);
pm.environment.set('payment_status', result.data.paymentStatus);
