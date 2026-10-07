# 支付消息与订单消费

第二版已接通 Payment Outbox 到 RabbitMQ 再到 Order 的付款通知链路。启用 MQ 后，Payment 自动停止旧 HTTP 通知任务；订单回查付款事实、库存确认、迟到付款冲正和出票继续使用已有业务流程。公开 API 不变。

## 完整顺序

1. 用户请求模拟付款成功，Payment 在同一个本地事务中保存付款事实和 `PENDING` Outbox 事件。
2. `PaymentOutboxJob` 每秒扫描最多二十条到期事件，用条件更新领取三十秒租约。领取后退出数据库操作，再执行网络发送。
3. `PaymentEventPublisher` 发送原 JSON、原事件编号、traceId，并设置消息持久化和 mandatory。
4. 收到 Broker Confirm ACK 且没有 Return，发送器才把事件标为 `PUBLISHED`。无法路由、超时或连接失败会保存错误并按五秒到五分钟退避重试。
5. Order 手动确认模式的监听器校验消息契约、头部编号与载荷编号，先在事务外通过 HTTP 和服务凭证回查 Payment 的权威事实。
6. `PaymentMessageTransaction.accept` 在同一个本地事务中保存 `t_consumed_event` 消费记录，并调用已有 `OrderPaymentReceiptTransaction.accept` 核对金额、用户、期限和付款依据。
7. 提交成功后才 ACK。已有订单后台恢复任务继续确认库存、保存成交和生成唯一电子票；库存已经释放时走原全额模拟冲正流程。

MQ 承载付款成功线索，没有替代业务状态和库存数据。Broker Confirm 表示消息已被 Broker 接收，消费者 ACK 表示付款依据已落库；这两者都不表示电子票已经生成。

## 按代码阅读

| 顺序 | 代码 | 职责 |
| --- | --- | --- |
| 1 | [付款与事件事务](../ticket-payment-service/src/main/java/com/byy/ticket/payment/service/impl/PaymentServiceImpl.java) | 原子保存付款成功和事件 |
| 2 | [Outbox Mapper](../ticket-payment-service/src/main/java/com/byy/ticket/payment/mapper/OutboxEventMapper.java) | 扫描、领取、发布完成和失败退避 |
| 3 | [发送任务](../ticket-payment-service/src/main/java/com/byy/ticket/payment/mq/PaymentOutboxJob.java) | 领取后发送，过期租约恢复，token与有效期保护状态更新 |
| 4 | [消息发送器](../ticket-payment-service/src/main/java/com/byy/ticket/payment/mq/PaymentEventPublisher.java) | 原消息发布，等待Confirm并检查Return |
| 5 | [MQ拓扑](../ticket-order-service/src/main/java/com/byy/ticket/order/mq/OrderMqConfig.java) | 主队列、三档延迟队列与死信队列；Payment声明相同拓扑 |
| 6 | [消费监听器](../ticket-order-service/src/main/java/com/byy/ticket/order/mq/PaymentMessageConsumer.java) | 验证、回查、事务接收、ACK、确认后转发失败消息 |
| 7 | [消费事务](../ticket-order-service/src/main/java/com/byy/ticket/order/mq/PaymentMessageTransaction.java) | 消费幂等记录与付款依据同事务 |
| 8 | [消费记录迁移](../ticket-order-service/src/main/resources/db/migration/V6__consumed_payment_events.sql) | 唯一键consumer与eventId、契约指纹和付款归属 |

发送成功但未能保存 `PUBLISHED`，或者订单提交后 ACK 丢失，都可能产生重复消息。这是至少一次投递；原 eventId、消费唯一键和原业务幂等共同防止重复执行业务。指纹按契约字段计算，JSON空白与字段顺序变化不会误判为不同消息。

## 重试和死信

默认交换机为 `ticket.payment.exchange`，类型为 durable direct。两端使用同一 `TICKET_MQ_PREFIX`，默认 `ticket`。

| 路由键 | 队列 | 用途 |
| --- | --- | --- |
| payment.succeeded | ticket.order.payment-succeeded | Order接收付款线索 |
| payment.retry.1 | ticket.order.payment-retry-1 | 十秒后重新进入主队列 |
| payment.retry.2 | ticket.order.payment-retry-2 | 三十秒后重新进入主队列 |
| payment.retry.3 | ticket.order.payment-retry-3 | 两分钟后重新进入主队列 |
| payment.dead | ticket.order.payment-dead | 重试耗尽或永久错误，等待核对 |

消费遇到支付服务不可用、超时或数据库写入失败，会将原载荷和编号发送到对应延迟队列。只有该转发收到Confirm且没有Return，才ACK主队列原消息。转发失败时显式NACK并重新入队，保留原消息。

格式、契约、归属冲突及缺少业务记录等永久错误直接进入死信。临时错误最多三档延迟重试后进入死信，死信没有自动消费者。排查付款和订单事实后，才重投同一事件。

延迟队列使用 quorum、`x-dead-letter-strategy=at-least-once` 和 `x-overflow=reject-publish`，避免默认死信转发的无确认窗口。配置依据见 [RabbitMQ quorum死信说明](https://www.rabbitmq.com/docs/quorum-queues#dead-lettering)。本机实际验证使用 RabbitMQ 4.3.6；单节点验证不代表多节点容灾能力。

## 启用与切换

默认 `TICKET_MQ_ENABLED=false`，第一版启动方式继续可用。开启步骤：

1. 启动 RabbitMQ，确认 AMQP端口可连接。本机安装目录是 `E:\DevTools\RabbitMQ\rabbitmq_server-4.3.6\sbin`，在配置好Erlang的终端运行 `rabbitmq-server.bat`。管理控制台与AMQP端口是不同入口，应用连接AMQP端口。
2. 配置下表环境变量，重启IDEA使新Windows用户变量生效。Payment与Order均设置 `TICKET_MQ_ENABLED=true`，使用相同MQ地址、虚拟主机和前缀。
3. 重新构建并启动Payment与Order。Flyway执行Payment V3与Order V6。升级迁移时停止全部旧实例，避免旧代码绕过事件或消费记录。
4. 按原注册、下单、创建支付单、模拟付款流程操作，最终查询本人订单和电子票。

| 变量 | 默认值或用途 |
| --- | --- |
| TICKET_MQ_ENABLED | false，true开启发送器和消费者 |
| LOCAL_RABBITMQ_HOST | 127.0.0.1 |
| LOCAL_RABBITMQ_PORT | 5672 |
| LOCAL_RABBITMQ_USERNAME、LOCAL_RABBITMQ_PASSWORD | 本机默认guest；使用已有Broker时填写该Broker账号 |
| LOCAL_RABBITMQ_VHOST | / |
| TICKET_MQ_PREFIX | ticket，隔离队列与交换机的前缀 |
| TICKET_MQ_RETRY_DELAYS_MS | 10000,30000,120000，三档延迟；同一拓扑两端必须一致 |

MQ关闭时旧HTTP任务按原配置工作；MQ开启时自动跳过该任务。历史HTTP进度仍保留，不能将其 `PENDING` 当作MQ未送达。Outbox的 `PUBLISHED`、Order消费记录及订单状态才是新链路各阶段的依据。

消费记录不能在可能重投历史消息期间随意清理。死信仍存在时也不要删除或改变交换机、队列类型和重试参数；拓扑变更需要明确迁移方案。

## 查看与恢复

只读核对：

```sql
-- 在ticket_payment库
SELECT event_id, aggregate_id, status, attempt_count, last_error
FROM t_outbox_event ORDER BY id DESC LIMIT 20;
-- 在ticket_order库
SELECT * FROM t_consumed_event ORDER BY consumed_at DESC LIMIT 20;
```

死信工具使用同一套MQ环境变量，每次处理一条：

```powershell
# 默认只查看并放回队列
.\deploy\mq\replay_payment_dead.ps1
# 确认原因已修复后，原编号重投；发布确认后删除原死信
.\deploy\mq\replay_payment_dead.ps1 -Replay
```

重投重置三档消费重试计数，保留原事件身份。不能通过修改消息里的订单、支付编号来处理业务冲突；未修复的坏消息会再次进入死信。

## 验证方法

MQ 已接入[完整分布式追踪](http-tracing.md)。Outbox 在付款事务中保存标准上下文，发送器创建 PRODUCER Span 并注入消息头，Order 创建 CONSUMER Span；延迟重试与死信重投保留 `traceparent` / `tracestate`。追踪模式 `verify_payment_mq.ps1 -Tracing` 通过226项检查，覆盖真实Tempo中的父子关系及原MQ故障场景。

本次真实RabbitMQ验证通过80项检查，支付本地事务与Outbox验证通过118项，原HTTP履约回归通过137项。MQ测试使用独立5679端口节点，业务服务使用随机端口；应用正常配置默认仍为5672。

先构建Order、Payment、Inventory及其依赖，运行 `deploy/verify/verify_payment_mq.ps1`。脚本使用实际RabbitMQ连接、随机数据库和唯一队列前缀，结束清理测试资源，不启动或停止用户的Broker。

验证覆盖付款到真实库存售出及电子票、重复投递、临时故障延迟重试、消费事务回滚、迟到付款冲正、发送租约过期重发、消费者重启、mandatory Return、重试耗尽、失败转发保留原消息，以及死信工具查看与重投。

原HTTP链路另通过 `deploy/verify/verify_payment_fulfillment.ps1` 的137项检查。公开接口不变，无需重新导入Apifox；新增流程由内部MQ承担。
