# 订单服务用户身份接入

## 本次完成

Order使用Spring Security的Servlet `SecurityFilterChain`，独立校验用户JWT签名、声明、Redis会话。认证通过后Spring Security在当前请求建立Principal，名称取JWT的sub（用户ID），已有OrderIdentityResolver和Controller继续使用，不重复校验账号密码、不读Auth用户库、不创建新用户。

支付通知入口 `POST /internal/orders/payment-results` 使用单独的Payment服务凭证；用户JWT不能访问这个入口，服务凭证不能调用用户订单接口。Payment的OrderClient增加专用凭证头，其可靠通知、查询权威支付事实、存储证据及幂等恢复保持原有流程。

Payment公共用户接口的Token认证仍未接入；本阶段并未完成所有服务内部入口的统一认证。

## 启动与配置

重新加载Maven，先启动Redis、MySQL、Nacos及Auth，再重启Order和Payment。Order默认8062，Payment默认8064。网关已有Token校验，转发原Authorization，Order再次独立校验；直接访问Order也不能绕过认证。

Order新增配置：

| 配置 | 说明 |
|---|---|
| ticket.security.jwk-set-uri | Auth公钥地址，默认http://127.0.0.1:8065/.well-known/jwks.json，可用AUTH_JWK_SET_URI覆盖 |
| ticket.security.issuer/audience | 与Auth签发值相同 |
| ticket.security.redis-prefix | 默认ticket:auth:，与Auth共享Redis会话协议 |
| spring.data.redis | LOCAL_REDIS_HOST/PORT/PASSWORD，默认127.0.0.1:6379无密码 |

网关与Order读取同一Redis实例/数据库中的会话，因此Auth退出删除会话后，两个服务都会拒绝旧凭证。公钥由解码器按需获取并缓存，Order不持有Auth私钥。

### Payment通知凭证

本机已经生成 `.local/service-credentials/payment-order.token`，Order和Payment默认读取此文件。其他环境在项目根目录执行一次：

```powershell
& 'C:\Users\RE\.jdks\ms-17.0.20\bin\java.exe' 'deploy/auth/GenerateServiceCredential.java'
```

生成器拒绝覆盖已有文件，不打印秘密内容。两服务必须加载同一个256位随机值；可以通过 `PAYMENT_ORDER_CREDENTIAL_PATH` 指定持久化文件绝对路径，或通过 `PAYMENT_ORDER_SERVICE_TOKEN` 提供64位小写十六进制值（环境变量优先）。默认相对路径要求IDEA工作目录为项目根目录。缺失凭证会启动失败，不会降级成匿名内部调用。

这是本地第一版的共享服务凭证方案，不是用户JWT、HMAC请求签名或完整的服务身份平台。跨主机部署时需通过TLS传输并独立分发凭证。私密文件已被 `.local/` gitignore排除。

## 代码阅读顺序

1. `ticket-order-service/config/OrderSecurityConfig`：两条有序安全链。第一条仅匹配/internal/**，仅POST支付结果可由PAYMENT_SERVICE访问，其余内部接口拒绝；第二条公共接口除GET ping外必须认证。
2. `OrderJwtConfig`：同步NimbusJwtDecoder获取公钥，配置超时和共享声明校验器，包装SessionCheckingJwtDecoder。
3. `ticket-security/jwt/AccessTokenClaimsValidator`：与网关复用的签发方、时间、受众、Access类型、用户ID、sid/jti规则。
4. `SessionCheckingJwtDecoder`：基础校验成功后再读取会话，返回Jwt供框架建立认证对象。
5. `AuthSessionReader`：StringRedisTemplate同步读取会话；不存在或归属不符为认证失败，连接故障抛独立存储异常。
6. `OrderIdentityResolver.requireUser`：读取request.getUserPrincipal().getName()，转换Long用户ID。原Controller继续把该ID传入创建、本人查询、本人电子票查询等业务。
7. `PaymentServiceAuthenticationFilter`：只安装在内部安全链，常量时间比较X-Payment-Order-Credential，创建服务Principal及专用角色，不作为用户身份。
8. `ticket-payment-service/config/PaymentOrderCredentialConfig`和`client/OrderClient`：加载相同凭证，在每次通知请求添加专用头。
9. `TraceIdFilter`：保留原追踪，新增Security阶段Redis故障的503 Result响应；Security配置分别处理401和403。

请求顺序：

```text
用户登录拿Access Token
  -> Gateway校验并转发原Token
  -> Order验签、声明、Redis会话校验
  -> Spring Security创建当前请求的Principal
  -> Controller调用OrderIdentityResolver
  -> 得到同一个登录用户ID
  -> 原订单业务执行归属判断
```

付款通知则是：

```text
Payment后台任务
  -> OrderClient添加独立服务凭证
  -> Order内部安全链验证并授予PAYMENT_SERVICE角色
  -> InternalOrderPaymentController
  -> 回查Payment权威事实并可靠保存
  -> 原库存确认、冲正、出票恢复流程
```

## 开发模式与原教学入口

默认 `ORDER_DEV_IDENTITY_ENABLED=false`，单独伪造X-User-Id或X-Dev-User-Id不能访问。为保留隔离测试，显式设置true、回环地址直连且完全不携带Authorization时，开发过滤器才接受合法正数X-Dev-User-Id。携带错误JWT时不会靠开发头绕过。Gateway删除这些外部头，因此不能通过网关使用该开发方式。生产保持关闭。

旧 `/internal/orders/stock-reservations` 是早期只验证远程预留的教学入口，本阶段安全链默认拒绝；正式创建使用 `/api/orders`。早期依赖匿名内部入口的库存Client/调用演示脚本不再作为当前系统准入验收。

## 验证

`deploy/verify/verify_order_auth.ps1` 本次37项检查通过：真实Auth签发、JWT下单、Principal归属优先于伪造头、购买幂等、跨用户查询404、错误签名/issuer/audience/类型/过期会话、退出失效、Redis故障503、服务凭证权限隔离、真实Payment可靠通知、真实库存确认与两张电子票。

测试仅活动使用HTTP规则桩，Auth/Order/Inventory/Payment使用真实MySQL/Redis和独立服务；关闭Nacos，固定SimpleDiscoveryClient实例。未在该脚本中运行Gateway；网关准入已有独立验证。所有数据用随机schema及会话前缀隔离，结束清理，仅关闭本次进程。

原支付交易回归的OrderPaymentVerification请求助手已更新为显式提供通知服务凭证，不再匿名调用付款通知接口。

`deploy/verify/verify_payment_fulfillment.ps1`本次135项成交与冲正回归也通过：重复通知、售出/冲正响应丢失、服务暂时不可用、业务事实不一致、远程已提交而订单保存失败、关单与库存确认竞争、重启和多实例恢复。该回归显式开启本机开发用户身份，新的37项验证使用真实用户JWT。
