// 下单和订单查询接口后置脚本。接受202，但不把它断言为最终成交。
const result = pm.response.json();
if (![200, 202].includes(pm.response.code) || result.code !== 'OK' || !result.data || !result.data.orderNo) {
    throw new Error('订单请求失败，保留原购买JSON及幂等键，检查响应');
}
pm.environment.set('order_no', result.data.orderNo);
pm.environment.set('order_status', result.data.status);
if (result.data.reservationId) pm.environment.set('reservation_id', result.data.reservationId);
