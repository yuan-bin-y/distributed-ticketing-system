// 创建活动前置脚本。正文用 {{draft_json}}，保持原JSON以保证重试幂等。
// 开始新一轮活动演示前，手动清空环境中的 draft_json。
let body = pm.environment.get('draft_json');
if (!body) {
    const now = Date.now();
    const localTime = offset => new Date(now + offset + 8 * 3600000).toISOString().slice(0, 19);
    const draft = {
        idempotencyKey: 'event_' + now + '_' + Math.random().toString(16).slice(2, 10),
        name: 'Apifox第一版演示活动', category: 'CONCERT',
        sessions: [{
            name: '演示场次', venueName: '体育馆', venueAddress: '演示地址',
            startTime: localTime(24 * 3600000), endTime: localTime(26 * 3600000),
            saleStartTime: localTime(-3600000), saleEndTime: localTime(12 * 3600000),
            purchaseLimit: 2,
            ticketTiers: [{ name: '普通票', price: 199, totalQuantity: 10 }]
        }]
    };
    body = JSON.stringify(draft);
    pm.environment.set('draft_json', body);
}
// 已有内容损坏时报错，不用新键悄悄创建另一个活动。
JSON.parse(body);
pm.variables.set('draft_json', body);
