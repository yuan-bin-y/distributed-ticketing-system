# 付款后的库存确认与无法履约冲正

## 当前完成的范围

在可靠付款通知的基础上，订单后台任务推进付款后的库存确认，正常成交进入 PAID；库存已经释放时，以原付款编号进行全额模拟冲正，确认完成后进入 REVERSED。网络失败、响应丢失或订单状态保存失败均根据持久化步骤恢复。

这是模拟支付的 HTTP 交易闭环，未接入真实支付渠道、认证、出票、累计限购、MQ 或 Outbox。矛盾数据、错误预留归属等进入 REVIEW_REQUIRED，保留事实供人工核对。

## 按代码顺序阅读

1. `OrderPaymentReceiptTransaction.accept()`：核实付款快照，在本地事务保存付款依据、PAYMENT_CONFIRMING、立即可执行的 next_attempt_at，并废止旧任务令牌。重复通知不重置已成交、待冲正或已冲正状态。
2. `OrderMapper.selectDue()/claim()`：原任务扫描扩展为 STOCK_PENDING、PENDING_PAYMENT、CLOSING、PAYMENT_CONFIRMING、REVERSAL_PENDING。多订单实例用原子 SQL 竞争领取；PAID、REVERSED、REVIEW_REQUIRED 不自动扫描。
3. `OrderStockWorkflow.advance()`：按当前状态分别处理库存预留、到期关闭、付款确认、冲正恢复。不会跨 HTTP 持有本地事务。
4. `OrderPaymentFulfillment.confirm()`：回查付款事实；查询原预留并核对原订单编号、场次、票档、数量和期限。RESERVED 调用已有 InventoryClient.confirm；SOLD 再核对付款后保存 PAID；RELEASED 则先保存 REVERSAL_PENDING 和固定冲正原因。
5. `OrderPaymentFulfillment.reverse()`：下一次领取再进入冲正步骤。先确认原库存仍为 RELEASED，再查询原付款。已有成功冲正直接保存原编号；否则调用 PaymentClient.reverse，核对订单、付款、金额、原因、成功状态和时间后保存 REVERSED。
6. `OrderMapper.finish()/finishReversal()`：只允许原状态与领取令牌仍匹配的任务完成步骤，旧任务不能覆盖新进度。
7. `OrderStockWorkflow.close()`：到期释放前通过 recordPaymentBeforeClose 回查支付；发现有效付款即可靠保存依据并转入库存核对，补救通知尚未送达的付款。支付服务暂时不可用时保留关闭进度重试；明确没有支付单或支付仍为 CREATED 才继续释放。

## 状态与数量

```text
付款依据已保存：PAYMENT_CONFIRMING
    ├─ 原库存 RESERVED → 确认 SOLD → PAID
    ├─ 原库存 SOLD + 有效付款 → PAID
    └─ 原库存 RELEASED → REVERSAL_PENDING
                              → 全额模拟冲正成功 → REVERSED
```

购买2张时，确认售出使预留数量减2、售出数量加2；释放使预留数量减2、可用数量加2。库存数量和预留记录仍由库存服务自己的本地事务一起更新，订单不访问库存库。

## 失败、重启与关闭竞争

- 确认已经售出但响应丢失：订单保持 PAYMENT_CONFIRMING；重试先查询原预留，见到 SOLD 后核对付款并保存 PAID，不重复扣库存。
- 支付已冲正但响应丢失：订单保持 REVERSAL_PENDING；重试查询原付款，读取同一冲正编号后保存 REVERSED，不重复退款。
- 远程步骤成功但订单更新失败：远程服务事实保留；订单根据原状态、编号和快照恢复，不将两个数据库包装成一个事务。
- 冲正请求先持久化固定原因，重试沿用原编号与原因，不重新生成请求身份。默认模拟开关关闭时，冲正保留 REVERSAL_PENDING 并继续重试；本地演示须显式开启模拟。
- 旧关闭请求可能在付款依据保存之前已经发到库存服务。清除令牌只能阻止旧订单更新，不能撤销在途 HTTP。库存 RESERVED→SOLD 与 RESERVED→RELEASED 互斥，任务根据最终库存核对：确认胜出则成交，释放胜出则冲正。
- 确认与释放冲突时不立刻误判失败，继续保留 PAYMENT_CONFIRMING；下次查询终态决定成交或冲正。
- 通知迟到时核对首次 paid_at，不能用通知抵达时间判断付款是否有效。已 CLOSED/CLOSING 且有原预留编号的有效付款重新进入库存核对；已释放则冲正，不重新抢占库存。
- 已有冲正却库存仍 RESERVED：释放后恢复冲正结果；冲正却库存已 SOLD 等矛盾情况进入 REVIEW_REQUIRED。独立手工模拟冲正不是完整主动退款功能；系统不撤销已售出票，不能承诺手工冲正与成交并发时自动完成业务退款。

通知 DELIVERED 仍只代表付款依据被订单接收，不代表履约已完成。网络异常仍采用持久化退避、幂等和核对恢复，没有消息队列。

## 数据库与启动

重启订单服务时 Flyway 执行新 V3，原 V1/V2 不变：

- 增加 reversal_reason、reversal_no 及冲正编号唯一键。
- 允许 PAID、REVERSAL_PENDING、REVERSED，约束成交/冲正必须有付款与预留依据，REVERSED 必须有冲正编号。
- 上一阶段因关闭竞争停在 REVIEW_REQUIRED 且已保存付款和预留编号的记录，升级时转入 PAYMENT_CONFIRMING；运行时仍核实全部快照，矛盾记录重新进入人工核对。
- 已 PAYMENT_CONFIRMING 的旧记录设为立即可恢复。
- 没有新增业务表，也没有跨服务外键。

重新加载 Maven，启动 MySQL、Nacos、Event、Inventory、Order、Payment 及 Gateway，沿用已有数据库环境变量。本地模拟付款和冲正开启 PAYMENT_SIMULATION_ENABLED=true，公共模拟接口另需 PAYMENT_DEV_IDENTITY_ENABLED=true 与 X-Dev-User-Id。正式创建订单使用已有 Order 开发身份配置。

订单后台任务默认每批后延迟5秒，租约增加到60秒。租约在启动时检查必须至少覆盖两次库存及两次支付调用的连接/读取超时总和，再留5秒余量。固定窗口和首次付款时间不会因重试延长。

手动验证流程：创建真实订单并取得预留 → POST /api/orders/{orderNo}/payments → POST /api/payments/{paymentNo}/simulate-success → GET /api/orders/{orderNo} 查看 PAID。后台推进可能需要数秒；接口均通过现有网关公共路由。尚未生成电子票。

## 验证依据

打包三个服务后运行 `deploy/verify/verify_payment_fulfillment.ps1`。本次通过122项检查：

- 三个真实服务进程和三个随机独立MySQL库；订单使用已落库夹具，库存预留、确认和释放均走真实库存接口，支付和冲正均走真实支付接口。
- V2→V3升级与上一阶段付款核对记录恢复。
- 正常成交、重复付款通知、库存确认响应丢失、冲正响应丢失、上下游暂时不可用、错误预留归属。
- 售出或冲正成功后订单更新失败，随后从原事实恢复。
- 未送达通知的付款在到期回查中恢复。
- 支付事务故意跨过到期时间，旧释放与新确认两个真实 HTTP 请求同时在途，分别验证释放胜出→REVERSED、确认胜出→PAID。
- 重启恢复待冲正记录、两个订单实例并发领取、真实自动支付通知链路、库存数量守恒。

测试使用 SimpleDiscovery + 真正 LoadBalancer，代理只注入故障与竞争；本次未重新验证真实 Nacos 注册或网关。测试不写默认业务库，结束只删除自身随机数据库并停止自身进程。
