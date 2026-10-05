# Auth 服务：用户表、登录与会话

## 当前完成范围

`ticket-auth-service` 独立运行，默认端口8065，通过Nacos注册；只访问自己的 `ticket_auth` 数据库。首次启动 JDBC 创建数据库，Flyway V1 建立 `t_user`。

已完成注册、登录、刷新、当前会话退出，RSA签名及公钥文档。网关路由与Token校验、订单和支付的Token接入、服务内部接口身份保护是下一阶段；目前仍需直连Auth验证接口，不能把Auth完成理解为整个系统身份接入完成。

本服务是项目自己的账号认证接口，不是完整OAuth2/OIDC授权服务器。

## 启动

1. 重新加载根Maven项目。保留已有 `LOCAL_MYSQL_USERNAME`、`LOCAL_MYSQL_PASSWORD` Windows环境变量。
2. 启动MySQL、Redis（默认127.0.0.1:6379）和Nacos。Redis会话Lua兼容当前本机Redis3.2；后续部署升级版本另外安排。
3. 在项目根目录一次性生成本地开发密钥：

```powershell
& 'C:\Users\RE\.jdks\ms-17.0.20\bin\java.exe' 'deploy/auth/GenerateAuthKeys.java'
```

文件位于 `.local/auth-keys/private.pem` 和 `public.pem`；`.local/` 已被gitignore排除。已有文件时生成器拒绝覆盖。私钥仅由Auth读取，勿复制给其他服务；密钥不能随每次启动重新生成。

4. IDEA运行类：`com.byy.ticket.auth.TicketAuthApplication`，模块 `ticket-auth-service`，Java17。工作目录设为项目根目录，默认相对密钥路径据此解析。若工作目录不同，可用绝对路径环境变量 `AUTH_JWT_PRIVATE_KEY_PATH` 和 `AUTH_JWT_PUBLIC_KEY_PATH`。
5. 验证 `http://localhost:8065/api/auth/ping`。

Redis可配置 `LOCAL_REDIS_HOST`、`LOCAL_REDIS_PORT`、`LOCAL_REDIS_PASSWORD`；不设置时使用本机6379且无密码。新增Windows环境变量后需重启IDEA使其继承。不要创建空的端口环境变量。

## 用户表

| 字段 | 说明 |
|---|---|
| id | 自增用户ID，其他服务只存此编号，不跨库建立外键 |
| username | 3至32位字母数字下划线，统一小写，唯一索引防止并发重复注册 |
| password_hash | BCrypt摘要，接口响应不返回 |
| nickname | 不超过64字符且不能为空 |
| status | ACTIVE/DISABLED |
| created_at/updated_at | 创建、更新时间 |

密码注册要求8至72字符，额外检查UTF-8最多72字节，避免BCrypt截断多字节密码。

## 接口

以下正文均使用 `Content-Type: application/json`，响应包装为 `Result`。

| 方法和路径 | 输入 | 输出/用途 |
|---|---|---|
| POST /api/auth/register | username、password、nickname | 返回用户ID、账号、昵称；不自动登录 |
| POST /api/auth/login | username、password | 返回accessToken、refreshToken、tokenType、expiresIn |
| POST /api/auth/refresh | refreshToken | 返回新的两种凭证；旧Refresh不可再用 |
| POST /api/auth/logout | 无需业务正文；Authorization: Bearer AccessToken | 删除当前会话，其他登录会话不受影响 |
| GET /api/auth/ping | 无 | 仅检查HTTP服务已启动 |
| GET /.well-known/jwks.json | 无 | 标准JWKS公钥结构，不包装Result，不含私钥 |

示例注册输入：

```json
{"username":"buyer01","password":"DemoPassword_123","nickname":"购票用户"}
```

错误：参数400、账号重复409、错误账号密码/无效凭证401、存储不可用503。

## 完整顺序与代码阅读

1. `AuthController.register` → `AuthServiceImpl.register` → BCrypt编码 → `UserMapper.insert`。仅MySQL本地事务。
2. `AuthController.login` → 查询用户 → 校验密码和ACTIVE状态 → `TokenService.issue` → `RedisSessionService.create` → 返回Token。
3. JWT使用RS256，sub是用户ID，sid是登录会话，jti是凭证编号；同时携带issuer、audience、签发/到期时间、access或refresh类型。
4. `JwtConfig`的Access解码器校验签名、时间、issuer、audience、类型及Redis会话。Redis不可用不能跳过会话校验。
5. 刷新：Refresh解码器验签/声明 → 查用户状态 → 签发新凭证 → Lua比较旧refreshJti并替换。多实例并发只有一个获胜，已经退出/到期的会话不会被重新建立。
6. 退出：Security先验证Access → `AuthServiceImpl.logout`删除该sid。该会话的Access和Refresh均失效；失败不报告退出成功。

Redis键为 `ticket:auth:{userId}:session:sid`，Hash保存userId及当前refreshJti；TTL随成功刷新延长。Access默认15分钟、Refresh7天，通过 `ticket.auth.access-ttl`、`refresh-ttl` 配置。

刷新只替换Refresh，不立刻撤销尚未过期的旧Access；退出会让同一sid的全部Access失效。禁用用户会阻止后续登录和刷新；当前没有管理员禁用/全部会话撤销接口，单独改数据库状态不会立即撤销已有Access，需后续补管理流程。当前没有验证码、找回密码、登录防暴力破解和密钥轮换管理。

## 验证

先打包Auth，再运行 `deploy/verify/verify_auth.ps1`。本次59项检查通过：真实MySQL/Redis、两个Auth实例、并发注册、并发Refresh单赢家、错误签名/issuer/audience/类型/到期凭证、退出失效、重启、会话过期及Redis不可达。使用独立随机测试数据库和Redis前缀，结束仅清理自己的数据，不执行FLUSHDB；此脚本关闭Nacos，未验证网关和Nacos集成。

JWT资源服务器的公钥验签与Principal映射参考 [Spring Security官方说明](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)。
