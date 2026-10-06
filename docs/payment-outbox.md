# 支付 Outbox 持久化

付款成功事实和待发布事件放在同一个支付数据库事务中保存。本页讲持久化部分；后续发送器和订单消费者也已接通，完整流程见[支付MQ与订单消费](payment-mq.md)。MQ开关关闭时事件保留在 `PENDING`，旧HTTP通知继续工作。

## 按代码顺序阅读

1. [V3 迁移](../ticket-payment-service/src/main/resources/db/migration/V3__payment_success_outbox.sql)创建 `t_outbox_event`。`event_id` 唯一标识一条消息，`event_type` 与 `aggregate_id` 的联合唯一键保证同一支付只有一个成功事件。载荷保存为 JSON，后续重发使用原载荷和编号。
2. [PaymentSucceededEvent](../ticket-payment-service/src/main/java/com/byy/ticket/payment/mq/event/PaymentSucceededEvent.java)定义消息字段：事件编号、类型、契约版本、订单编号、支付编号。消息提供付款成功线索；订单仍需按原流程回查支付事实，不能只凭消息认定金额和付款归属。
3. [PaymentServiceImpl](../ticket-payment-service/src/main/java/com/byy/ticket/payment/service/impl/PaymentServiceImpl.java)的 `simulateSuccess` 先锁定支付记录。首次成功更新付款状态与旧 HTTP 通知进度，再调用 `outbox.recordSuccess`；已有成功付款调用 `requireExisting` 核对原事件。
4. [PaymentOutboxService](../ticket-payment-service/src/main/java/com/byy/ticket/payment/service/PaymentOutboxService.java)生成稳定事件并保存。`Propagation.MANDATORY` 要求加入调用方已有事务，序列化或插入失败会使付款更新一起回滚。重复付款只检查事件，不重建载荷、不重置发布进度。
5. [OutboxEventMapper](../ticket-payment-service/src/main/java/com/byy/ticket/payment/mapper/OutboxEventMapper.java)负责写入和查询本库事件；[OutboxEvent](../ticket-payment-service/src/main/java/com/byy/ticket/payment/model/OutboxEvent.java)对应表结构。

```text
模拟付款请求
  → 锁定支付单
  → 更新 SUCCESS、首次付款时间和 HTTP 待通知状态
  → 插入 PENDING Outbox 事件
  → 同一事务提交
```

这部分避免“付款记录提交成功，但待发送事件没有保存”。本地事务本身不负责网络发送，也不保证订单已处理；发布确认、消费幂等与 ACK 边界由[MQ链路](payment-mq.md)负责。

## 表中字段的用途

| 字段 | 用途 |
| --- | --- |
| event_id、event_type、aggregate_id | 消息身份、事件类型、所属支付编号 |
| payload、trace_id | 原消息内容与链路追踪编号 |
| status | PENDING、SENDING、PUBLISHED、REVIEW_REQUIRED |
| attempt_count、next_attempt_at、last_error | 后续发送重试进度 |
| lease_token、lease_until | 后续多实例发送器领取任务和恢复过期租约 |
| created_at、published_at | 事件建立时间与发布完成时间 |

发送任务使用后三类字段记录发布进度。HTTP 通知的 `DELIVERED` 与 MQ 的 `PUBLISHED` 含义不同，HTTP 通知成功不会把 Outbox 改成已发布。付款状态、库存预留数量和售出数量等业务事实继续保留。

## 本地升级

升级前停止全部旧 Payment 实例，避免迁移后旧代码继续写入没有事件的成功付款。重新构建并启动新版 Payment 后，Flyway 自动执行 V3。

V3 对全部历史 `SUCCESS` 支付回填一条 `PENDING` 事件，包括已 HTTP 通知和已模拟冲正的付款；它们仍具有原付款成功事实。历史事件编号和追踪编号按支付编号计算，未付款记录不生成成功事件。后续消费者需要安全处理重复通知以及已关闭、已成交等订单状态。

第一版归档仍为 12 张业务表；第二版新增支付 Outbox 和订单消费记录，共 14 张业务表，另有五个库的 Flyway 历史表。公开 API 不变，无需重新导入 Apifox 文档。

## 验证结果

支付模块 Maven 构建通过。运行 `deploy/verify/verify_payment.ps1`，在随机隔离 MySQL 库中通过 118 项检查，结束后删除该测试库：

- 真实 V2 到 V3 升级，分别回填待通知和已通知的成功付款；未付款不回填。
- 20 次并发付款只有一条成功事件，重复操作保留原编号、载荷和发布进度。
- 注入 Outbox 插入失败后，付款成功状态、首次付款时间和 HTTP 待通知状态一起回滚；重试可以成功。
- 重启后原付款、冲正和事件仍存在，迁移不会再次复制事件。
- 原支付 HTTP 契约、金额、权限、到期边界与模拟冲正检查继续通过。

上述118项专门验证支付本地事务，没有连接 RabbitMQ。真实MQ验证及HTTP兼容回归见[MQ说明](payment-mq.md)。
