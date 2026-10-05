# 同一用户、同一场次累计限购

## 当前规则

Event 的 `t_event_session.purchase_limit` 已经定义每人每场次上限。Order 通过 EventClient 获取规则，在自己的数据库维护累计额度；同一场次不同票档合并计算，不同用户、不同场次分别计算。

占用包括 STOCK_PENDING、PENDING_PAYMENT、CLOSING、PAYMENT_CONFIRMING、REVERSAL_PENDING、PAID、COMPLETED、REVIEW_REQUIRED。处理结果不确定或待核对时保留额度。CREATE_FAILED、CLOSED、REVERSED 在确认对应事实后归还额度。已成交及出票的订单继续占用。

当前没有主动退款或人工修复接口；人工核对状态不会自动归还额度。规则调整不改写已有订单数量，历史累计超过新上限时仍完整保留，只阻止新的占用。预览只检查本次请求和活动规则，不占用额度，也不保证后续下单必定成功。

## 数据与代码阅读顺序

1. `ticket-order-service/src/main/resources/db/migration/V5__add_purchase_quota.sql`：创建 `t_user_session_quota`，以 `(user_id,session_id)` 为主键，保存 `occupied_quantity`；订单增加 `quota_status`，HELD表示仍占用，RELEASED表示已归还。历史订单按快照回填，不清空原有购买数量。
2. `mapper/PurchaseQuotaMapper.java`：初始化、条件占用和归还。占用SQL要求 `occupied_quantity + quantity <= limit`，更新成功才允许提交；并发请求由数据库行锁串行裁决。
3. `service/OrderTransactionService.create()`：原有订单和订单项先插入，再占用额度，三者在一个本地事务中提交。超限返回409并回滚候选订单。重复幂等键沿用原订单，不再占用额度。
4. `service/OrderStockWorkflow.complete()`：明确创建失败或库存释放后，调用 `finishAndReleaseQuota()`。其他进度保持原来的条件更新。网络超时不提前归还。
5. `service/OrderPaymentFulfillment.reverse()`：核实原库存已释放、冲正已成功后，调用 `finishReversalAndReleaseQuota()`，一起保存REVERSED与额度归还。
6. `OrderTransactionService` 的归还方法：先锁定订单行，再核对前置状态和领取令牌，只有HELD才减额度并标记RELEASED。旧任务、重复执行、已关闭订单收到迟到付款通知，均不能重复归还。

```text
Event通过HTTP返回场次规则
    ↓
Order本地事务：订单 + 订单项 + 额度占用
    ↓ 提交
HTTP请求Inventory预留 → 原支付、恢复与出票流程
    ↓ 确认失败/关闭/冲正
Order本地事务：终态 + 额度归还 + RELEASED标记
```

所有额度操作只访问Order数据库，不在事务内发送HTTP。归还SQL数量不足或标记更新失败会回滚整个终态事务，原步骤仍可核对重试，不能把账目异常伪装成关闭成功。

## 升级运行

重新加载 Maven。先停止全部旧 Order 实例，再启动新版 Order，Flyway 自动执行V5，默认创建在 `ticket_order`。其他业务服务不需要新增表或环境变量。

迁移必须在订单写入停止时执行，避免旧实例继续写入未占用额度的订单。迁移会汇总仍占额度的历史订单；缺少购买快照的历史活跃订单会使迁移失败，需要先核对数据。MySQL DDL不能整体回滚，失败后须检查已执行步骤和Flyway记录，再进行修复，不能直接删除迁移记录反复启动。

## 验证

`deploy/verify/verify_purchase_quota.ps1` 通过95项检查，包含原真实Auth、Order、Inventory、Payment购票闭环，以及20次并发限购、跨票档、跨场次、跨用户、重复幂等键、成交继续占用、关闭后重购、写入/归还故障回滚、不确定库存调用保留额度和V4历史订单升级回填。

测试使用独立进程、随机MySQL schema和Redis会话前缀，结束只清理自己的数据。活动为HTTP规则桩，关闭Nacos并使用固定服务实例。这是正确性检查，不是性能压测报告。

`deploy/verify/verify_payment_fulfillment.ps1` 同步通过137项检查，覆盖成交、迟到付款、库存释放与确认竞争、冲正响应丢失、重启恢复及额度账目核对。该脚本的旧订单夹具升级为V4 → V5迁移验证。
