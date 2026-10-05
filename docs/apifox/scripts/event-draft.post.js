// 创建草稿/查询准备/发布接口后置脚本。管理端标识不是公共查询的id字段。
const result = pm.response.json();
if (pm.response.code !== 200 || result.code !== 'OK' || !result.data || !result.data.eventId) {
    throw new Error('活动请求失败，检查响应；不覆盖已有活动编号');
}
const draft = result.data;
pm.environment.set('event_id', String(draft.eventId));
pm.environment.set('event_status', draft.status);
const tiers = [];
for (const session of draft.sessions || []) {
    for (const tier of session.ticketTiers || []) tiers.push(tier);
}
if (draft.sessions && draft.sessions[0]) {
    pm.environment.set('session_id', String(draft.sessions[0].sessionId));
}
if (tiers[0]) pm.environment.set('ticket_tier_id', String(tiers[0].ticketTierId));
pm.environment.set('inventory_ready', String(tiers.length > 0 && tiers.every(t => t.preparationStatus === 'READY')));
// inventory_ready=true后手工发送发布请求；本脚本不自行发布。
