# 支付服务身份接入

## 这次完成什么

Payment 的公共查询和模拟成功接口，现在和 Order 一样验证 Auth 签发的 Access Token：公钥验签、声明校验、Redis 会话校验，成功后建立当前请求自己的 Principal。业务层读取 Principal 的用户ID，检查支付单归属；用户不能通过正文或请求头指定另一个人的身份。

内部接口单独验证 Order 服务凭证。订单创建支付单、回查支付事实、发起冲正，与支付通知订单分别使用两个独立凭证：

| 调用方向 | 请求头 | 默认凭证文件 |
| --- | --- | --- |
| Order → Payment：创建、查询、冲正 | X-Order-Payment-Credential | .local/service-credentials/order-payment.token |
| Payment → Order：付款通知 | X-Payment-Order-Credential | .local/service-credentials/payment-order.token |

用户 Token 不能访问这些内部接口，服务凭证也不能冒充用户操作公共支付接口。业务事务、支付金额快照、通知重试和库存恢复继续沿用原流程；本次没有新增数据库表或迁移。

## 按顺序看代码

1. `ticket-payment-service/pom.xml`：增加 Security、JWT资源服务器、Redis依赖。
2. `application.yml`：配置 Auth 公钥地址、JWT issuer/audience、Redis会话前缀与 Order 服务凭证路径。
3. `config/PaymentJwtConfig.java`：组装 JwtDecoder，复用 ticket-security 的声明校验和会话校验。
4. `config/PaymentSecurityConfig.java`：内部和公共请求各走一条安全链。内部只放行已授权的创建、查询、冲正方法；公共除 GET ping 外必须有用户身份。
5. `web/PaymentIdentityResolver.java`：读取本请求 Principal；原有 PaymentServiceImpl 继续检查支付单的 userId。
6. `web/OrderServiceAuthenticationFilter.java`：验证内部服务凭证，建立 ORDER_SERVICE 身份，不建立购票用户身份。
7. `ticket-security/service/OrderPaymentCredential.java`：读取持久化随机凭证，常量时间比较；缺失或格式错误时启动失败。
8. `ticket-order-service/client/PaymentClient.java`：创建 RestClient 时加上专用服务请求头，三个内部调用统一携带凭证。
9. `web/filter/TraceIdFilter.java`：认证阶段 Redis 不可用返回 503，并保留统一响应与 traceId。

正常调用顺序：

```text
登录 → Auth 返回 Access Token
    → Gateway 验证 Token、转发 Authorization
    → Order 再次验证、读取当前用户、创建订单
    → PaymentClient 携带 Order 服务凭证创建支付单
    → 用户携带 Token 调用 Payment 模拟成功
    → Payment 验证身份和归属、记录支付事实
    → Payment 使用另一凭证通知 Order
    → Order 回查事实、确认库存、出票
```

## 本机运行

重新加载 Maven，重启 Order 和 Payment。保持工作目录为项目根目录；两份本地凭证已经生成，内容不写入代码或日志，`.local/` 已被 Git 忽略。

先运行 MySQL、Redis、Nacos 和 Auth，再运行业务服务及 Gateway。正常用户接口携带 `Authorization: Bearer <accessToken>`。仅模拟付款演示需要设置 `PAYMENT_SIMULATION_ENABLED=true`，开发身份开关保持默认关闭。

新环境可在项目根目录分别生成文件，已有文件不要再次生成：

```powershell
java deploy/auth/GenerateServiceCredential.java .local/service-credentials/payment-order.token
java deploy/auth/GenerateServiceCredential.java .local/service-credentials/order-payment.token
```

Order 与 Payment 必须读取相同方向的同一份凭证。可通过 `ORDER_PAYMENT_CREDENTIAL_PATH` 指定新文件路径，或以 `ORDER_PAYMENT_SERVICE_TOKEN` 配置64位小写十六进制随机凭证；原通知方向继续使用 `PAYMENT_ORDER_CREDENTIAL_PATH` / `PAYMENT_ORDER_SERVICE_TOKEN`。

本地旧教学回归可显式打开 `PAYMENT_DEV_IDENTITY_ENABLED=true`。只有无 Authorization 的回环直连请求才能接受 X-Dev-User-Id；携带错误 Token 不能降级到开发身份。

## 已验证及范围

`deploy/verify/verify_payment_auth.ps1` 通过79项检查：真实 Auth 签发、公共用户认证、归属隔离、伪造头、错误签名/类型/issuer/audience/会话、退出失效、服务凭证隔离及 Redis 故障503。真实 Gateway → Order → Payment 的下单、模拟成功、可靠通知、库存确认和电子票查询闭环通过。

验证使用随机数据库和 Redis 会话前缀，结束清理自身进程、数据库和会话。活动规则使用 HTTP 桩；测试关闭 Nacos，业务调用使用固定 SimpleDiscoveryClient 实例，网关使用固定 HTTP 路由。因此本次测试不重复证明 Nacos 注册。

这是本地第一版的共享服务凭证方案。跨主机需要 TLS 和独立分发凭证；Event/Inventory 内部入口仍未接入服务认证。真实支付渠道、Outbox/MQ 和完整服务身份管理留待后续阶段。

原成交、冲正及故障恢复验证 deploy/verify/verify_payment_fulfillment.ps1 同步通过135项检查。
