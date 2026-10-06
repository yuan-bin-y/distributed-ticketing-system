# 全项目业务HTTP调用保护

## 当前范围

五条现有业务调用链已接入Resilience4j熔断和并发隔离；HTTP超时、服务凭证、traceId及响应校验继续保留。

| 调用链 | 保护的实际操作 | 客户端 |
| --- | --- | --- |
| Order → Event | 查询购票规则 | EventCallGuard → EventClient |
| Order → Inventory | 预留、查询预留、确认售出、释放 | InventoryClient |
| Order → Payment | 创建支付单、按订单查询支付事实、冲正 | PaymentClient |
| Event → Inventory | 使用原初始化快照初始化或幂等核对库存事实 | InventoryInitializationClient |
| Payment → Order | HTTP模式发送付款通知并核对可靠接收确认 | OrderClient |

Event目前通过原参数重发幂等初始化命令及其返回事实完成核对，本轮未新增初始化GET客户端。Gateway路由沿用此前网关限流；JWT公钥获取、Nacos客户端、Redis和MQ属于框架或其他协议，不套用该业务HTTP组件。MQ开启后付款通知沿用Confirm、ACK和消费幂等机制。

## 代码阅读顺序

1. `ticket-resilience/pom.xml`：不独立启动的基础模块，只包含技术配置与保护组件，没有业务实体、Controller或数据库。
2. `ticket-resilience/.../HttpResilienceProperties.java`：按库存、支付、订单下游分别绑定、校验策略。
3. `ticket-resilience/.../HttpCallProtection.java`：复用并发额度、熔断统计和试探恢复机制。
4. 各服务的客户端构造器：创建自己的单例保护器，提供故障分类以及本地拒绝的异常转换。
5. 客户端公开方法：先校验本地参数，再经过保护组件执行原HTTP和响应校验。
6. 原有业务工作流：按业务冲突、结果未知或暂时不可用保存进度，稍后核对或恢复。

Order到Event仍保留`EventCallGuard`与原配置，底层改为复用同一`HttpCallProtection`。其余客户端在内部包装调用，所有现有业务入口和恢复任务调用它们时都获得保护，避免只保护Controller请求、遗漏后台请求。

```text
客户端公开方法
    → 校验参数
    → 申请下游并发额度
    → 检查下游熔断状态
    → 发送HTTP与校验响应
    → 更新熔断统计
    → 归还并发额度
```

业务请求和恢复任务共享同一客户端的额度。每个调用方/下游独立，例如Order到Inventory与Event到Inventory互不共用统计；同一个Order内库存四种操作共用库存保护器。统计位于本JVM，重启后重置，不是Redis集群级并发额度。

## 配置与开关

除原有Order→Event配置外，使用`ticket.http-resilience.<下游>`前缀。

| 应用 | 下游配置 | 开关环境变量 |
| --- | --- | --- |
| Order | inventory | ORDER_INVENTORY_RESILIENCE_ENABLED |
| Order | payment | ORDER_PAYMENT_RESILIENCE_ENABLED |
| Event | inventory | EVENT_INVENTORY_RESILIENCE_ENABLED |
| Payment | order | PAYMENT_ORDER_RESILIENCE_ENABLED |

默认均为true。Order→Event继续使用`EVENT_RESILIENCE_ENABLED`及`ticket.clients.event.resilience.*`，详见[Event保护说明](event-resilience.md)。

共享策略的默认值：

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| window | 20 | 按调用次数统计的最近结果窗口 |
| minimum-calls | 10 | 判断失败率的最少结果数 |
| failure-threshold | 50 | 失败率达到50%时打开熔断器 |
| open-wait | 10s | 熔断后的暂停时间 |
| half-open-calls | 3 | 恢复阶段允许的试探调用数 |
| half-open-max-wait | 10s | 试探阶段最长等待 |
| concurrency | 20 | 本JVM对该下游的同时调用数 |

例如启动Order时通过参数覆盖库存策略：

```text
--ticket.http-resilience.inventory.concurrency=10
--ticket.http-resilience.inventory.open-wait=5s
```

并发满额立即拒绝，不额外排队。暂停结束由后续调用触发试探，不额外主动发送健康请求。状态变更记录保护器名称，方便区分下游。

所有参数都在启动绑定时校验；直接Java构造策略也校验正数、窗口关系和等待期限。当前默认值是学习项目的初始参数，需要结合实际负载调整，不代表生产容量。

## 异常分类

- 明确参数拒绝、资源不存在、库存不足、幂等参数冲突等合法业务结果不计作下游故障，也不作为成功降低失败率。
- 超时、连接失败、没有可用实例、5xx、损坏JSON和不符合契约的成功响应计作故障。
- 远端4xx保持原有业务处理或错误契约；若业务错误正文损坏或错误码矛盾，不能当作已确认的业务事实，仍按异常响应处理。
- 本地熔断拒绝或并发拒绝没有发送HTTP，不计为新的下游失败。Order客户端转换为原有UNAVAILABLE，公开请求通常返回503；后台任务沿用原暂时不可用恢复流程。
- Event收到初始化明确拒绝仍进入人工核对状态；初始化响应与快照矛盾也保留原暂停处理，同时纳入健康失败统计。保护组件本地拒绝则保留准备进度，避免错误标记为库存已经准备完成。
- Payment的HTTP通知遇到拒绝、超时或不匹配accepted时仍保存通知恢复进度，不能标记通知已可靠接收。

## 写请求与一致性

必须区分两个情况：

```text
保护组件拒绝 → 本次没有发出HTTP → 保留进度稍后尝试
HTTP发出后超时 → 远端可能已提交 → 使用原业务编号核对事实
```

保护器不自动重试任何调用，不制造备用成功结果。库存预留沿用原订单编号，支付创建沿用原订单编号，冲正沿用原付款编号和首次原因，库存初始化沿用原票档/场次/数量快照，付款通知沿用原订单和支付编号。

一条请求被拒绝不表示之前的请求未成功。即使本次未发送，也必须保留原恢复流程对历史不确定结果的核对。原本的本地事务、幂等、租约、持久化进度及补偿继续保证业务最终一致；熔断和隔离只保护调用资源。

## 验证与复现

2026-10-07验证通过：全模块构建成功；Event专项61项，其余四条调用链专项182项；六服务活动与购买回归104项，支付履约及故障恢复137项。共包含243项专项检查与241项业务回归检查。

构建全部模块后执行：

```powershell
& deploy/verify/verify_event_resilience.ps1
& deploy/verify/verify_http_resilience.ps1
& deploy/verify/verify_event_administration.ps1
& deploy/verify/verify_payment_fulfillment.ps1
```

两个专项使用真实HTTP和随机端口故障服务，检查统计、熔断时无HTTP、试探恢复、并发额度、业务拒绝排除、损坏响应、身份与trace传播以及写请求丢失响应后不自动重发。跨下游专项也核对各下游配置独立绑定。

响应丢失专项只证明客户端没有自动重发，模拟提交计数不代替数据库事务测试。真实服务回归使用隔离MySQL库、服务进程及Redis前缀，继续核对活动准备、下单、付款、出票、库存确认和冲正响应丢失、迟到付款及关闭竞争。

验证日志保存在本地忽略目录，脚本清理自己启动的端口和资源。范围为本机功能与故障验证，未验证多机故障容量或长时间负载。
