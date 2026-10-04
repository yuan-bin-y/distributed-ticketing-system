# 支付成功通知与订单付款依据

## 本阶段完成什么

支付成功后，后台任务可靠通知订单。订单回查支付服务核实用户、金额、期限、付款编号与首次成功时间，再在本地事务保存付款依据。响应丢失会产生重复通知，接收方按已保存依据幂等确认。

通知接收后订单进入 `PAYMENT_CONFIRMING`，表示依据已保存。后续后台确认库存、处理关闭竞争及恢复冲正已经接入，见 [成交与冲正恢复](payment-fulfillment.md)；最终成交或冲正必须以库存和支付事实核实。

通知已送达只表示订单已可靠保存依据；它既不代表库存 SOLD，也不代表订单 PAID。

## 按代码顺序阅读

1. `PaymentServiceImpl.simulateSuccess()`：同一本地事务保存 SUCCESS、paid_at、notify_status=PENDING、next_notify_at。
2. `PaymentNotificationJob.reconcile()`：默认每批结束后等待5秒，扫描待通知记录。
3. `PaymentNotificationWorkflow.recoverDue()/advance()`：通过支付 Mapper 原子领取任务，HTTP 期间不持有数据库事务。
4. `OrderClient.notifySuccess()`：按服务名 ticket-order-service 调用 `POST /internal/orders/payment-results`，传播 X-Trace-Id。报文只有 orderNo、paymentNo，不跨模块共享业务实体。
5. `InternalOrderPaymentController.receive()`：接收并校验通知线索。
6. `OrderPaymentReceiptService.receive()`：通过 `PaymentClient.getByOrder()` 调用 `GET /internal/payments/by-order/{orderNo}`，核实支付服务真实记录。
7. `OrderPaymentReceiptTransaction.accept()`：独立事务 Bean 锁定订单，检查成功状态、金额、用户、期限、付款编号和 paid_at；付款必须发生在原期限之前。保存 payment_no、paid_at、订单状态，并废止旧租约令牌。
8. 事务提交后 Controller 返回 `Result<PaymentReceiptVO>`，其 accepted=true 表示依据已保存。
9. `OrderClient` 核对业务码 OK、accepted=true、原订单编号及付款编号。
10. 支付 Mapper 只有在领取令牌匹配时才保存 DELIVERED；错误或未知响应仍为 PENDING，并持久化指数退避时间。

```text
支付 SUCCESS + PENDING
    → 后台领取 → HTTP通知订单
    → 订单HTTP回查支付 → 订单本地事务保存依据
    → 接收确认 → 支付 DELIVERED
```

## 失败与并发

- 通知 HTTP 失败、上游不存在、非法确认、响应丢失等均继续重试，不直接放弃付款事实。退避从5秒开始，上限5分钟；没有超过次数自动丢弃。
- 接收后响应丢失：订单依据已经提交，支付仍为 PENDING；下次回查并幂等返回原接收确认。
- 订单保存失败：本地事务回滚，不能返回 accepted=true，支付继续重试。
- 多支付实例：条件 SQL 原子领取，每条记录持有30秒租约；进程退出后租约到期可重领。旧令牌不能覆盖新任务进度。租约不能取消已经发送的 HTTP，因此接收幂等仍必需。
- 付款早于期限但通知迟到：以支付记录的 paid_at 核实，不能以通知抵达时间判断未付款。
- 正常待支付订单：进入 PAYMENT_CONFIRMING，不再参加原 STOCK_PENDING/PENDING_PAYMENT/CLOSING 扫描。
- 已 CLOSING/CLOSED 且有原预留编号：保存依据并进入 PAYMENT_CONFIRMING，后台根据原库存终态成交或冲正。缺少预留依据等异常进入 REVIEW_REQUIRED。旧关闭请求可能已经到达库存服务，清除令牌不能撤回网络请求，因此付款不保证必然获得票。
- 已付款依据的重复通知不重算时间、不覆盖正常进度；已成交后发现外部冲正进入 REVIEW_REQUIRED，其他冲正事实由履约步骤核对。

独立模拟冲正本身不会重新发送已经 DELIVERED 的成功通知。本阶段还没有冲正结果通知链路；订单发起的冲正已有持久化恢复，不能把独立手工模拟冲正解释为自动同步订单。

PAYMENT_CONFIRMING 和 REVERSAL_PENDING 已有自动后续处理，矛盾事实仍停在 REVIEW_REQUIRED。内部接口沿用本地可信环境约定，公共网关不转发 /internal；服务间身份认证随后接入。

## 数据库与启动

原 V1 不变，重启 Order 与 Payment 时 Flyway 自动执行各自 V2：

- Order：t_order 增加 payment_no、paid_at、付款编号唯一键与依据成对约束，状态约束允许 PAYMENT_CONFIRMING。
- Payment：t_payment 增加 notify_lease_token、notify_lease_until，复用原通知状态、次数、下次时间和错误字段。
- 没有新增业务表，也没有跨服务外键。

重新加载 Maven，启动 Nacos、Order 与 Payment。沿用已有 Windows MySQL 环境变量；模拟公共接口另需 PAYMENT_SIMULATION_ENABLED=true、PAYMENT_DEV_IDENTITY_ENABLED=true，并传 X-Dev-User-Id。

通知默认开启，可通过 PAYMENT_NOTIFICATION_ENABLED=false 暂停后台发送，PENDING 数据保留。其他环境变量：PAYMENT_NOTIFICATION_FIXED_DELAY_MS 默认5000、ORDER_NOTIFICATION_CONNECT_TIMEOUT 默认2s、ORDER_NOTIFICATION_READ_TIMEOUT 默认10s。配置修改超时时，租约必须至少覆盖连接与读取超时总和，再留5秒余量。

## 验证

打包 Order 和 Payment 后运行 `deploy/verify/verify_payment_notification.ps1`。使用随机独立 MySQL 库、独立服务进程、SimpleDiscovery + 真正 LoadBalancer 的 HTTP 链路，通知代理注入失败与响应丢失；没有写默认业务库。订单为已预留状态的数据库夹具，本次未操作真实库存服务。

本次通过106项检查，覆盖 V1→V2 升级、权威回查、伪造与不匹配通知、并发重复通知、错误确认、事务回滚、接收后响应丢失、迟到通知与关闭状态竞争、旧关单令牌失效、冲正事实、重启恢复、多支付实例领取。测试结束只清理自身随机数据库和进程。本次未重新验证真实 Nacos 注册或网关路由。

## 下一阶段

订单恢复任务已推进 PAYMENT_CONFIRMING，核对库存状态并确认售出；库存已释放时走持久化冲正恢复，见 [成交与冲正恢复](payment-fulfillment.md)。第一版 HTTP 闭环完成后再引入 MQ + Outbox。
