# 活动缓存、热点重建与网关限流

## 完成的功能

活动分页、详情、场次与票档展示使用 Redis 缓存。分页只缓存前十页，内部购票规则仍查询 Event 数据库；订单价格、库存扣减、累计限购和支付事实沿用原有交易逻辑。

缓存保存 VO，Controller 每次重新包装 Result，避免缓存别人的 traceId。Redis 故障时活动查询受控回源，数据库并发额度用尽时返回 503；不会无限放行回源请求。

## 按请求顺序看代码

1. `ticket-event-service/.../service/impl/EventServiceImpl.java`：决定哪些展示查询走缓存，哪些查询直接查库。
2. `ticket-event-service/.../cache/EventQueryCache.java`：读取展示版本和缓存；未命中时用 `SET NX PX` 抢当前 key 的重建锁。
3. `ticket-event-service/.../service/impl/EventDatabaseQueries.java`：获得重建权限后执行原有数据库查询。数据库事务只包住查询，不包住 Redis 等待。
4. `EventQueryCache.save`：Lua 核对随机 token，原子写缓存并释放锁。锁过期的旧持有者不能覆盖新持有者写入的数据，也不能释放别人的锁。
5. `ticket-event-service/.../service/EventDraftTransaction.java`：活动发布成功后登记提交回调。数据库真正提交后，切换 Redis 展示版本，使各实例的列表、详情、场次及空结果缓存一起失效。
6. `ticket-gateway/.../web/GatewayRateLimitFilter.java`：在 Security 认证完成后，按可信用户身份或直连 IP 执行限流，再决定是否转发业务请求。

### 重建期间

同一个 key 的其他请求短暂等待缓存出现；超过等待期限返回 `503 SERVICE_BUSY`。锁有租约：持有者崩溃后其他请求可重建，查询超过租约时可能发生重复回源。该锁用于减少展示查询，交易正确性仍由数据库事务、幂等和恢复流程保证。

空结果缓存 5 秒，正常缓存 TTL 加随机抖动，减少不存在的活动反复查库和大量 key 同时到期。损坏的 JSON 仅在值仍相同时删除并重新加载。版本键不存在时生成新的随机版本，避免重新命中旧版本数据。

### 发布与展示一致性

发布失败、事务回滚不切换缓存版本。发布提交后切换版本，旧查询即使晚返回，也只会写入旧版本 key，等待 TTL 回收。

如果提交后 Redis 失效通知失败，记录告警，展示数据按 TTL 更新；不会把已提交的发布操作报告成数据库回滚。展示采用最终一致性，内部购票规则保持查库。当前项目没有已发布活动编辑、下架接口；新增这些功能时也需要在提交后失效。

## 默认配置

Event 的 `application.yml`：

| 属性 `ticket.event-cache.*` | 默认值 | 含义 |
| --- | --- | --- |
| enabled | true | 环境变量 `EVENT_CACHE_ENABLED` 可关闭 |
| prefix | `ticket:{event-cache}:` | 同一套服务共享；不同环境必须隔离 |
| ttl-ms | 60000 | 实际正常 TTL 为 60～72 秒 |
| wait-ms | 1500 | 重建竞争等待期限，另有 Redis 操作超时 |
| lease-ms | 8000 | 重建锁租约 |
| database-concurrency | 16 | 每个 Event 实例的展示查询回源并发额度 |

`ticket.event-cache.metrics-enabled=true` 可开启缓存计数日志，默认关闭。计数包括命中、回源、等待、Redis 失败及拒绝；不作为精确 SQL 审计。

Gateway 的 `ticket.rate-limit.*`：

| 范围 | 全局每秒补充 / 桶容量 | 每用户或 IP 每秒补充 / 桶容量 |
| --- | --- | --- |
| 活动 GET 查询 | 200 / 400 | 20 / 40 |
| 订单请求 | 100 / 200 | 5 / 10 |

令牌桶允许短时突发。全局和调用方两个桶由同一个 Lua 脚本原子检查、扣减；不同网关实例共享 Redis 额度。使用 Redis 时间计算补充，桶空闲后自动过期。默认 key 前缀包含 Redis Cluster hash tag，多 key 脚本的 key 必须处于同一 slot。

限流启用变量为 `GATEWAY_RATE_LIMIT_ENABLED`，前缀变量为 `GATEWAY_RATE_LIMIT_PREFIX`；也可以通过 Spring 配置覆盖各项速率。上述数值是本地学习项目起点，不能视作生产容量。

## 返回与身份边界

- 超额返回 `429 RATE_LIMITED`，附带 `Retry-After: 1` 和当前请求 traceId。
- 限流 Redis 无法访问或脚本执行超过 500ms，返回 `503 SERVICE_BUSY`，不继续转发。
- 活动缓存重建等待超时或回源额度用尽，返回 `503 SERVICE_BUSY`。
- 订单请求仍先完成认证，匿名订单请求返回 401。
- 限流覆盖活动 GET 和订单请求；`/ping`、认证、管理员活动、支付接口不在本轮覆盖范围。

匿名流量使用网络连接的真实对端 IP，不信任客户端填写的 `X-Forwarded-For` 或 `X-User-Id`。Gateway 设置 `server.forward-headers-strategy: none`。如果部署到反向代理后方，需要先设计可信代理边界；当前策略下匿名用户可能共享代理 IP 额度。

网关使用 `ReactiveStringRedisTemplate`，没有同步 Redis 调用或 `.block()`。Event 是 Spring MVC，使用同步 Redis 客户端。

## 验证与压测

构建 Event、Gateway 及依赖后，在项目根目录执行：

```powershell
& deploy/verify/verify_event_cache_load.ps1
```

脚本读取已有 `LOCAL_MYSQL_*`、`LOCAL_REDIS_*` 环境变量，创建随机测试库、Redis 前缀和测试端口；结束后清理自己创建的资源，不执行 FLUSHDB。需要本机 MySQL、Redis，以及 MySQL `performance_schema` 查询权限。

验证包括两个独立 Event 和两个独立 Gateway 进程、冷热点重建、稳定命中、空结果、发布失效、租约过期、事务回滚、缓存损坏、受控回源、共享限流、伪造身份头和 Redis 不可用。SQL 压力通过 MySQL 业务 SELECT 汇总对照。

库存准备使用隔离数据库夹具，限流的订单转发使用 HTTP 探针；该脚本验证缓存与准入，不代替完整支付出票验收。原有活动管理验收单独运行，继续核对真实库存初始化及交易闭环。
