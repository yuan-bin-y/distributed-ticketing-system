# 全项目分布式追踪：HTTP、MQ 与后台恢复

六个服务均接入 Micrometer Tracing + OpenTelemetry，通过 OTLP HTTP 导出到 Tempo，由 Grafana 展示调用时间线。覆盖 Gateway、Auth、Event、Inventory、Order、Payment，以及支付 Outbox、MQ 消费和后台库存确认、出票。

## 整体流程

```text
Gateway → Payment 模拟付款成功
                  ↓ 同事务保存支付事实、Outbox、追踪上下文
          Outbox 发送器恢复上下文，创建 PRODUCER Span
                  ↓ 发布消息并等待 Confirm / Return
              RabbitMQ
                  ↓ 标准消息头传递上下文
          Order 创建 CONSUMER Span
                  ↓ 回查 Payment、提交付款依据与消费记录、ACK
          同事务保存消费上下文
                  ↓ 后台任务恢复上下文，创建独立 Span
          Inventory 确认售出 → Order 出票
```

每次发布、投递和后台尝试都有独立 Span ID。延迟重试转发也创建 PRODUCER Span，死信重投保留标准头。业务正确性仍由原事务、状态机、Confirm/ACK 和幂等保证。

登录、创建订单、发起支付、模拟付款成功通常是不同 Trace；延续的是某次请求引发的异步工作，并非把用户整个购票过程合成一条 Trace。

## 代码与迁移

新增 `ticket-observability` 是支持库，不启动。`TraceSupport` 封装上下文捕获、恢复、Span 生命周期与异常标记。

| 代码入口 | 作用 |
| --- | --- |
| 六服务 pom.xml / application.yml | 观测依赖、服务名、采样和 OTLP 配置 |
| GatewayTraceFilter / 各服务 TraceIdFilter | 当前 Span → 响应头、日志 MDC、Result |
| Event 的 InventoryInitializationClientConfig | Boot 配置 RestClient，启用自动客户端观测 |
| EventDraftTransaction / EventPreparationWorkflow | 草稿保存上下文，后台库存准备恢复上下文 |
| OrderTransactionService / OrderStockWorkflow | 创建订单保存上下文，预留及恢复创建 Span |
| PaymentOutboxService / PaymentEventPublisher | 支付事务保存上下文，发送时恢复并注入消息头 |
| PaymentMessageConsumer | 提取 MQ 上下文，覆盖回查、事务、ACK 和重试转发 |
| OrderPaymentReceiptTransaction | 首次接受付款依据时保存消费上下文，重复通知不覆盖 |
| PaymentNotificationWorkflow | 保留的 HTTP 通知模式也恢复 Outbox 上下文 |
| deploy/mq/PaymentDeadLetterReplay.java | 保留标准头，兼容 RabbitMQ LongString |
| deploy/tracing/ | Tempo、Grafana、自动数据源和隔离验证 Broker |

Flyway 仅增加可空字段，不新增业务表：

| 迁移 | 表 | 新字段 |
| --- | --- | --- |
| Event V3 | t_ticket_tier | trace_parent、trace_state |
| Order V7 | t_order | trace_parent、trace_state |
| Payment V4 | t_outbox_event | trace_parent、trace_state |

重启服务自动执行各自迁移。旧记录缺少标准上下文时，后台工作建立新 Trace，不能恢复未保存的历史父 Span。

## 与旧 traceId 的区别

`traceparent` 包含 Trace ID、父 Span ID 和采样标志，`tracestate` 随上下文传递；数据库和 MQ 保留这些标准字段。响应 `X-Trace-Id`、`Result.traceId` 展示真实当前 Trace ID，日志 MDC 包含 traceId / spanId。

真实 Span 存在时，任意旧 X-Trace-Id 不会覆盖它。无效标准头建立新 Trace，编号不作为认证凭证。WebFlux 使用 Reactor Observation 上下文，MVC 过滤器在服务端 Observation 后读取 Span。

## 本地启动

项目根目录运行：

```powershell
docker compose -f deploy/tracing/compose.yml up -d
```

若终端找不到 docker，本机可用 `$env:LOCALAPPDATA\Programs\DockerDesktop\resources\bin\docker.exe` 的完整路径。

| 地址 | 用途 |
| --- | --- |
| http://localhost:3001/explore | Grafana，选择 Ticket Tempo |
| http://127.0.0.1:3200/ready | Tempo 就绪检查 |
| http://127.0.0.1:4318/v1/traces | OTLP HTTP 导出 |

容器使用独立数据卷与 3001 端口，不影响已有 Grafana。端口仅绑定本机，本地匿名 Viewer 可使用 Explore。

重新加载 Maven，在六个服务 IDEA 启动配置中添加以下变量，或设置 Windows 用户变量后重启 IDEA：

```text
TICKET_TRACING_EXPORT_ENABLED=true
TICKET_TRACING_SAMPLE_RATE=1.0
```

重新启动六个服务，原 Nacos、MySQL、Redis 和服务凭证继续按启动指南配置。MQ 模式还需在 Order / Payment 设置 `TICKET_MQ_ENABLED=true` 并启动 RabbitMQ；应用默认 Broker 端口仍为5672。

| 变量 | 默认值 |
| --- | --- |
| TICKET_TRACING_EXPORT_ENABLED | false |
| TICKET_TRACING_SAMPLE_RATE | 0.1 |
| TICKET_OTLP_ENDPOINT | http://127.0.0.1:4318/v1/traces |

## Grafana 查询与边界

复制响应头 X-Trace-Id 或正文 traceId，进入 Explore，选择 Ticket Tempo，把编号粘贴到 TraceQL 输入框并查询。也可搜索 `{ resource.service.name = "ticket-order-service" }`。

付款成功 Trace 展示 HTTP、发布、消费、支付事实回查、库存确认和后台出票。不同 HTTP 请求仍各有编号。不是所有4xx都标记ERROR，业务拒绝同时查看HTTP状态属性。

当前未逐条建立 SQL Span，未启用 Service Graph 指标生成器；服务总耗时包含数据库操作，不能拆出 SQL 耗时。异步跨度含调度等待，不等于接口响应时间。

采样未命中可能只有编号而没有 Tempo 数据。Span 异步批量上报，需要等待几秒；进程崩溃或队列满可能丢失观测数据，追踪不能作为事务凭证。

## 实际验证

2026-10-07：全模块 Maven 编译打包成功。

- `verify_tracing.ps1 -Full`：561项检查通过，包含六服务真实 HTTP、Outbox、MQ、保存付款依据、库存确认、出票与父子 Span 关系。
- `verify_payment_mq.ps1 -Tracing`：226项检查通过，包含延迟重试、重复发布、重启、mandatory Return、回滚、失败转发保留原消息、重试耗尽及死信重投。

测试使用随机数据库、唯一队列及真实 RabbitMQ / Tempo，结束清理自身数据库、会话、队列和服务进程。HTTP 验收关闭 Nacos，使用简单服务发现；生产配置继续使用 Nacos。

Grafana 已实际展示付款 Trace `47fad5f40133452995a6c4698f52a477`：四个业务服务、48个Span，包含发布、消费、库存确认和出票。这是单次功能验证，不是容量结论。Tempo 测试数据保留24小时。

本地证据：`.local/full-tracing-verification-result.json`、`.local/full-trace-<id>.json`、`.local/mq-tracing-verification-result.json`、`.local/grafana-full-tracing.png`。

重新验证：

```powershell
# 隔离验证 Broker，端口5679
docker compose -f deploy/tracing/compose.yml --profile verification up -d rabbitmq-verification
& deploy/verify/verify_tracing.ps1 -Full
$env:LOCAL_RABBITMQ_PORT='5679'
& deploy/verify/verify_payment_mq.ps1 -Tracing
# 仅停验证 Broker，保留 Tempo / Grafana
docker compose -f deploy/tracing/compose.yml --profile verification stop rabbitmq-verification
```

脚本读取已有 Windows 数据库/Redis用户变量，仅在测试进程设置导出和采样。基础HTTP验证可单独运行 `verify_tracing.ps1`。

## 官方资料

- [Spring Boot 4.0 Tracing](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html)
- [Spring Boot 配置属性](https://docs.spring.io/spring-boot/4.0/appendix/application-properties/index.html)
- [Micrometer Tracing API](https://docs.micrometer.io/tracing/reference/api.html)
- [Spring AMQP Observation](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/micrometer-observation.html)
- [Tempo 2.9 本地配置](https://github.com/grafana/tempo/blob/v2.9.0/example/docker-compose/local/tempo.yaml)
