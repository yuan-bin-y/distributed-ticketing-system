// 普通用户登录/刷新接口的后置脚本。不要打印完整Token。
const result = pm.response.json();
if (pm.response.code !== 200 || result.code !== 'OK'
    || !result.data || !result.data.accessToken || !result.data.refreshToken) {
    throw new Error('登录/刷新失败，保留原用户凭证，检查响应');
}
pm.environment.set('access_token', result.data.accessToken);
pm.environment.set('refresh_token', result.data.refreshToken);
