// 管理员登录/刷新接口的后置脚本。使用独立用例，避免覆盖用户Token。
const result = pm.response.json();
if (pm.response.code !== 200 || result.code !== 'OK'
    || !result.data || !result.data.accessToken || !result.data.refreshToken) {
    throw new Error('管理员登录/刷新失败，检查账号和响应');
}
pm.environment.set('admin_token', result.data.accessToken);
pm.environment.set('admin_refresh_token', result.data.refreshToken);
