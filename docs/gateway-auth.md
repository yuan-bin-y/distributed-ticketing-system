# 网关认证：JWT与响应式Redis会话检查

## 已完成范围

网关增加 `/api/auth/** -> lb://ticket-auth-service`，默认端口仍8060。用户登录后携带 `Authorization: Bearer <accessToken>`，网关先检查JWT签名、声明及Redis会话，再按原路由转发。

新增 `ticket-security` 公共认证库，没有启动类、数据库、用户实体或独立端口。网关通过Maven依赖使用它，后续业务服务可复用声明规则。

网关准入已完成，Order随后也已独立接入Token及会话校验，见 [订单身份接入](order-auth.md)。网关Principal不会跨HTTP传播，Order从原Token建立自己的Principal。Payment公共用户接口及其余内部服务身份仍待接入。

## 代码阅读顺序

1. `ticket-gateway/src/main/resources/application.yml`：新增Auth路由、Redis连接、公钥URI、issuer/audience及会话前缀。
2. `GatewaySecurityConfig.securityWebFilterChain`：WebFlux安全规则。POST注册/登录/刷新、GET活动/认证ping允许匿名；其他接口必须认证；网关显式禁止 `/internal/**`。
3. `GatewayJwtConfig.accessTokenDecoder`：创建RS256基础解码器，通过WebClient按需获取JWKS并设置连接/响应超时，Nimbus缓存公钥。
4. `ticket-security/AccessTokenClaimsValidator`：无过期宽限的时间及issuer校验，检查audience、access类型、有效Long用户ID、UUID格式sid/jti以及必要时间声明。只做内存判断，不同步访问Redis。
5. `ReactiveAuthSessionReader.isActive`：使用ReactiveStringRedisTemplate读取 `ticket:auth:{userId}:session:sid` 的userId。匹配为true，键或字段不存在为false；存储故障作为单独异常上抛。
6. `SessionCheckingReactiveJwtDecoder.decode`：基础解码通过后 `flatMap` 串接会话查询，只有true才返回Jwt；Security再建立用户Principal。没有block、手工subscribe或同步Redis调用。
7. `GatewaySecurityResponses` / `GatewaySecurityExceptionHandler`：凭证无效401、无权访问403、会话存储不可用503，统一JSON Result，避免故障时绕过认证。
8. `GatewayTraceFilter`：traceId放入exchange属性及请求/响应头，通过异步处理传递，不使用Servlet线程MDC。`UntrustedIdentityHeaderFilter`删除外部的开发用户头和普通用户头，但保留Authorization。

## 请求顺序

```text
请求进入Gateway
  -> 建立traceId
  -> Security提取Bearer JWT
  -> 获取/使用缓存公钥，验证RS256签名
  -> 校验时间、issuer、audience、access类型、用户与会话编号
  -> 响应式查询Redis会话
  -> Security建立网关Principal
  -> 删除外部伪造身份头，保留Token
  -> Gateway路由转发
```

退出由Auth删除会话，网关下一次认证查询到缺失会话便返回401。匿名活动查询不会主动查询Redis；若客户端携带无效Bearer，Security仍会尝试验证该凭证并拒绝。

## 配置与启动

先启动MySQL、Redis、Nacos、Auth，再运行 `TicketGatewayApplication`，重新加载根Maven项目使 `ticket-security` 被识别。

共享本机Redis变量：`LOCAL_REDIS_HOST`、`LOCAL_REDIS_PORT`、`LOCAL_REDIS_PASSWORD`，默认127.0.0.1:6379无密码。网关不需要MySQL账号或Auth私钥。

| 网关配置 | 默认值 | 要求 |
|---|---|---|
| ticket.security.issuer | ticket-auth-service | 与Auth一致 |
| ticket.security.audience | ticket-api | 与Auth一致 |
| ticket.security.redis-prefix | ticket:auth: | 与Auth相同会话协议和Redis实例/数据库 |
| ticket.security.jwk-set-uri | http://127.0.0.1:8065/.well-known/jwks.json | Auth实际公钥地址，可用AUTH_JWK_SET_URI覆盖 |
| ticket.security.jwk-timeout | 3s | 获取公钥的连接及响应超时 |

Auth对外路由使用Nacos服务名；目前公钥地址使用可配置固定URI。这是两个不同用途的地址，不应直接把lb://作为JWKS的普通HTTP地址。缓存命中的已知公钥可继续验证原签名；冷启动无公钥且公钥源不可用时拒绝认证。当前没有密钥轮换管理。

### 可以验证的用户流程

```text
POST http://localhost:8060/api/auth/register
POST http://localhost:8060/api/auth/login
POST http://localhost:8060/api/auth/refresh
POST http://localhost:8060/api/auth/logout  (Bearer Access Token)
```

活动GET可匿名访问，无Token访问订单/支付会被网关返回401。Order现在可从转发Token取得真实用户ID；Payment公共用户接口尚未接入Token，仍可能返回401。网关不转发X-Dev-User-Id，不能依赖该头调用支付，下一步接入Payment用户身份。

## 验证结果与边界

打包Auth和Gateway后运行 `deploy/verify/verify_gateway_auth.ps1`。本次44项检查通过，覆盖真实Auth签发及注册/登录/刷新/退出路由、真实MySQL/Redis、独立网关进程、签名/时间/声明/Token类型、会话归属、退出失效、匿名活动、拒绝internal、移除伪造身份头、保留Bearer、公钥缓存、公钥源故障和Redis故障。

业务下游是HTTP桩，仅检查准入/转发，未验证真实订单或支付的用户Principal；测试关闭Nacos并使用固定测试路由。随机测试库和Redis前缀结束后清理，仅关闭本次进程，不停止用户Redis，不操作业务库或FLUSHDB。

响应式JWT配置与校验流程参考 [Spring Security官方文档](https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html)。
