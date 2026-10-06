# Order调用Event：熔断与并发隔离

## 完成范围

新下单和价格预览查询购票规则时，经过 Resilience4j 熔断与并发隔离。默认开启，设置 `EVENT_RESILIENCE_ENABLED=false` 可关闭保护。其余业务HTTP调用也已接入，见[全项目调用保护](http-resilience.md)。本页保留Order到Event的配置说明，底层统一复用`ticket-resilience`。

使用 Resilience4j 2.4.0 的 `circuitbreaker`、`bulkhead` 核心模块，在本项目 Java 17 上构建和运行验证。版本由父 POM 的 BOM 管理，Java 配置直接组合调用，不使用 Spring AOP 注解。版本发布信息见[官方发布说明](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0)。

## 按顺序阅读代码

| 文件 | 作用 |
| --- | --- |
| 父 `pom.xml` 与 Order 的 `pom.xml` | 管理版本并引入两个核心模块 |
| `ticket-order-service/.../config/EventResilienceProperties.java` | 绑定及校验配置 |
| `ticket-order-service/.../client/EventCallGuard.java` | 每个Spring单例构建一次保护器，组合并发额度与熔断器 |
| `ticket-order-service/.../client/EventClient.java` | 保留服务发现、HTTP、凭证、traceId、超时和响应校验 |
| `ticket-order-service/.../service/impl/OrderServiceImpl.java` | 下单及预览调用保护组件 |
| `ticket-order-service/.../client/exception/EventServiceCallException.java` | 区分参与熔断统计的远端故障和不参与统计的4xx |

```text
OrderController
    → OrderServiceImpl
    → EventCallGuard
        → Bulkhead申请并发额度，满额立即拒绝
        → CircuitBreaker检查是否允许调用
        → EventClient发送HTTP并解析规则
        → 熔断器记录调用结果
        → 归还并发额度
```

并发隔离在外层：Order本地额度不足，不会进入熔断统计，也不会发送HTTP。熔断拒绝仍会正确归还已申请的并发额度。

保护组件不会产生备用价格、伪造购票规则，也不会把暂时不可用转换成票档不存在。现有 HTTP 连接和读取超时继续生效；不引入额外自动重试或异步 TimeLimiter。配置与语义参考[熔断器](https://resilience4j.readme.io/docs/circuitbreaker)及[并发隔离](https://resilience4j.readme.io/docs/bulkhead)官方文档。

## 配置

Order 的 `application.yml` 中，前缀为 `ticket.clients.event.resilience`。

| 属性 | 默认值 | 含义 |
| --- | --- | --- |
| enabled | true | 是否使用保护组件 |
| sliding-window-size | 20 | 最近20次参与统计的已完成调用 |
| minimum-number-of-calls | 10 | 至少有10次统计结果才判断失败率 |
| failure-rate-threshold | 50 | 失败率达到50%时熔断 |
| open-wait | 10s | 熔断后暂停调用的时间 |
| half-open-calls | 3 | 恢复试探最多放行3次 |
| half-open-max-wait | 10s | 试探阶段的最长等待，避免迟迟无法得出结果 |
| max-concurrent-calls | 20 | 本Order实例同时进行Event调用的上限 |

使用按调用次数统计的窗口。保护器在每个 Order JVM 中独立，重启后统计重置；两个Order实例各自有20个额度，并非整个集群共享20个。与Gateway的共享Redis限流用途不同。

正常状态允许调用。达到失败阈值后打开熔断器，暂停调用；等待结束后，由下一次请求触发半开试探，再根据试探结果的失败比例决定关闭或重新打开。没有请求时不主动发送健康探测。状态转换写入Order的INFO日志。

启动时校验正数、失败比例、窗口与最少调用数的关系及正等待期限，错误参数会导致启动失败。

## 故障分类与HTTP契约

| 情况 | 调用Event HTTP？ | 参与故障统计？ | 对外响应 |
| --- | --- | --- | --- |
| 正常规则 | 是 | 记录成功 | 原有业务结果 |
| 票档不存在，Event 404 | 是 | 排除，不当作成功 | 404 |
| Event其他4xx | 是 | 排除，不当作成功 | 沿用502异常上游响应契约 |
| 本地票档ID不合法 | 否 | 排除 | 400 |
| Event 5xx、连接失败、没有可用实例 | 故障类型决定 | 记录失败 | 503 |
| HTTP读取超时 | 已尝试 | 记录失败 | 504 |
| JSON损坏、规则不完整或归属错误 | 是 | 记录失败 | 502 |
| 熔断器拒绝 | 否 | 不记录为新下游失败 | 503 UPSTREAM_UNAVAILABLE |
| 并发额度已满 | 否 | 不记录为下游失败 | 503 UPSTREAM_UNAVAILABLE |

统计仅纳入 EventClient 归一化的远端故障；其他本地异常不被当作Event健康指标。两种保护拒绝仍以原有 `UNAVAILABLE` 类别交给统一异常处理器，提示与原始cause区分原因，响应保留当前traceId。

新订单流程先查购买幂等键，再获取Event规则，之后才保存订单主表和价格快照。因此规则查询被拒绝时不会创建新订单；已经存在的同幂等键订单沿用原有返回与恢复流程，不必重新查询Event。库存和支付的持久化进度、核对、补偿机制保持原有职责；熔断不保证跨服务事务一致性。

## 验证

2026-10-07本机验证：专项61项检查通过，六服务业务回归104项通过，Order及依赖构建成功。

先构建Order及依赖，再执行：

```powershell
& deploy/verify/verify_event_resilience.ps1
& deploy/verify/verify_event_administration.ps1
```

专项使用真实Spring MVC、RestClient、LoadBalancer及HTTP故障服务，随机端口，不需要MySQL、Redis或Nacos，也不更改生产认证。仅测试探针允许匿名访问，生产服务没有新增探针入口。

专项核对最少样本、50%失败边界、404与4xx排除、熔断时无HTTP、成功恢复、失败试探重新熔断、损坏响应、超时、缺失实例、连接拒绝、并发拒绝不计作故障、在途请求正常结束、并发额度归还、关闭开关以及凭证和trace传递。

业务回归使用六个真实服务、隔离MySQL库和Redis前缀，验证发布、库存准备、下单、模拟付款与出票；Order使用本轮默认保护配置。专项采用较小窗口和等待时间加速故障验证，不能用其耗时证明生产容量。
