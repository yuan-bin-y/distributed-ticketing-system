// 下单前置脚本，正文用 {{order_json}}。同一次购买重试不改变任何字段。
// 新购买前手动清空 order_json；首次执行读取 ticket_tier_id 与 quantity。
let body = pm.environment.get('order_json');
if (!body) {
    const tierId = Number(pm.environment.get('ticket_tier_id'));
    const quantity = Number(pm.environment.get('quantity') || '2');
    if (!Number.isSafeInteger(tierId) || tierId < 1 || !Number.isSafeInteger(quantity) || quantity < 1) {
        throw new Error('先取得有效ticket_tier_id，quantity必须为正整数');
    }
    body = JSON.stringify({
        ticketTierId: tierId, quantity,
        idempotencyKey: 'buy_' + Date.now() + '_' + Math.random().toString(16).slice(2, 10)
    });
    pm.environment.set('order_json', body);
}
JSON.parse(body);
pm.variables.set('order_json', body);
