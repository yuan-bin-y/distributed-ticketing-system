# 支付服务与订单创建支付单

## 当前范围

ticket-payment-service 默认端口8064，独立连接ticket_payment。提供创建支付单、模拟付款成功、支付结果查询、全额模拟冲正；订单已通过PaymentClient接入创建支付单，见 [订单发起支付](order-payment-create.md)。支付服务已经可靠发送成功通知，订单回查并保存付款依据，正常进入 PAYMENT_CONFIRMING，后续确认库存或恢复冲正见 [成交与冲正恢复](payment-fulfillment.md)。

当前支付单可用于独立演示，不代表完整付款购票流程已经可用。正常付款依据保存后进入库存确认；关闭竞争根据原库存终态成交或全额模拟冲正，矛盾事实进入 REVIEW_REQUIRED。订单模拟支付交易链路已接通，电子票尚未生成。真实认证、真实支付渠道、MQ 和 Outbox 尚未实现。

## 启动

先启动 MySQL 和 Nacos。IDEA 重新加载根 Maven 工程，启动类选择：

- 模块：ticket-payment-service
- 类：com.byy.ticket.payment.TicketPaymentApplication
- Java：17
- 默认端口：8064

沿用 Windows 用户变量 LOCAL_MYSQL_USERNAME、LOCAL_MYSQL_PASSWORD。PAYMENT_DB_URL 可覆盖 JDBC 地址；默认使用 createDatabaseIfNotExist=true 自动建库，Flyway 执行 V1__create_payment_tables.sql 建表。数据库账号需要建库权限。

默认公共查询需要服务端可信 Principal，模拟成功和模拟冲正均关闭。只在本地演示时，在 Payment 的运行配置中设置：

~~~text
PAYMENT_SIMULATION_ENABLED=true;PAYMENT_DEV_IDENTITY_ENABLED=true
~~~

第二个开关允许 X-Dev-User-Id；X-User-Id、正文 userId 均不能用作公共接口身份。未来接入 Auth 后优先使用服务端 Principal，并关闭开发身份开关。

也可在一个 PowerShell 窗口设置临时变量后从该窗口启动：

~~~powershell
$env:PAYMENT_SIMULATION_ENABLED = 'true'
$env:PAYMENT_DEV_IDENTITY_ENABLED = 'true'
& 'C:\Users\RE\.jdks\ms-17.0.20\bin\java.exe' -jar ticket-payment-service/target/ticket-payment-service-1.0-SNAPSHOT.jar
~~~

这里的变量只影响此窗口启动的进程，不会自动传给已经打开的 IDEA。

探测地址：http://localhost:8064/api/payments/ping。网关新增 /api/payments/** -> lb://ticket-payment-service，重新启动网关后可从 http://localhost:8060/api/payments/ping 探测。Ping 只确认HTTP入口，不证明支付闭环完成。

## 两张业务表

### t_payment

- payment_no、order_no 唯一，一张订单只有一张支付单。
- user_id、amount、expires_at 来自订单调用方的快照；金额固定人民币，DECIMAL(18,2)。
- status：CREATED 或 SUCCESS。
- paid_at 保存首次成功时间；重复成功不改写它。
- notify_status：NONE、PENDING、DELIVERED；本阶段没有发送器，只生成 PENDING。
- next_notify_at、notify_attempt_count、last_notify_error 保存通知退避与错误；V2增加通知租约，支持多实例领取及宕机恢复。
- created_at、updated_at 由数据库维护。

SUCCESS 与 paid_at、PENDING、next_notify_at 在同一条 SQL、同一本地事务中写入。DELIVERED 将来仅表示接收方可靠接收结果，不等于订单已履约。

### t_payment_reversal

- reversal_no 唯一；payment_id 唯一且只关联本库支付单。
- amount 从支付单读取，全额冲正；不接受调用方提供金额。
- reason 保存首次原因，重复请求核对同一原因。
- status：PENDING -> SUCCESS；当前纯模拟，在同一个事务中完成。
- completed_at 是模拟完成时间。

冲正后支付单仍为 SUCCESS，以保留曾经付款的事实；查询结果同时返回 reversalNo、reversalStatus。当前没有真实外部资金操作，后续真实渠道接入需单独设计未知结果核对。

Flyway 另有 flyway_schema_history；本模块合计两张业务表和一张迁移历史表。

## 接口契约

| 方法与路径 | 调用方及作用 |
| --- | --- |
| POST /internal/payments | 订单调用方创建支付单，金额由订单快照确定 |
| GET /internal/payments/by-order/{orderNo} | 订单调用方核对支付事实及冲正结果 |
| POST /internal/payment-reversals | 订单调用方决定无法履约后请求全额模拟冲正 |
| GET /api/payments/{paymentNo} | 当前用户查询自己的支付单 |
| POST /api/payments/{paymentNo}/simulate-success | 显式开启模拟后，当前用户模拟付款成功 |
| GET /api/payments/ping | 独立启动和路由探测 |

内部接口与已有库存接口一致，当前尚无服务间认证；网关只路由 /api/payments/**，不会转发 /internal/**。只允许可信内部调用方提供订单编号、用户、金额与期限；支付服务本阶段不向订单查询真实性。

创建正文：

~~~json
{
  "orderNo": "0123456789abcdef0123456789abcdef",
  "userId": 1,
  "amount": 398.00,
  "expiresAt": "2030-01-01T15:30:00.000"
}
~~~

上面的日期只是格式示例，演示时使用当前东八区时间之后的期限。所有编号使用32位小写十六进制。期限精度不超过毫秒，年份1000至9999；金额大于零、最多两位小数且不超过9999999999999999.99。

模拟成功接口不接收金额或付款时间；用户传入额外正文也不能修改支付快照。只有当前用户的支付单可付款，缺少身份返回401，其他用户或不存在的编号返回404。

冲正正文：

~~~json
{
  "paymentNo": "实际创建返回的32位支付编号",
  "reason": "订单已关闭且库存已释放"
}
~~~

reason 必须非空、最多256字符；同一支付单重试应使用相同原因。未成功付款不能冲正。开关关闭时，模拟成功及模拟冲正返回404，不改变业务记录。

统一响应为 Result<PaymentVO> 或 Result<PaymentReversalVO>。参数错误400，不存在404，参数/状态冲突409，暂时性数据库锁竞争503，其他异常500。X-Trace-Id 进入响应头和Result，处理结束清理线程MDC。

## 代码阅读顺序

1. TicketPaymentApplication、application.yml、PaymentConfig：启动、数据库、Nacos、模拟开关与时钟。
2. V1__create_payment_tables.sql：支付与冲正记录、唯一约束、状态约束。
3. InternalPaymentController、PaymentController、InternalPaymentReversalController：HTTP入口。
4. PaymentService：本服务能力定义。
5. PaymentServiceImpl.create：插入候选支付单，再锁定原记录；重复请求核对快照。
6. PaymentServiceImpl.simulateSuccess：先锁定支付单、检查用户，再保存支付成功和待通知。
7. PaymentServiceImpl.reverse：先锁定支付单，再创建并完成模拟冲正，异常整体回滚。
8. PaymentMapper、PaymentReversalMapper：唯一键插入、行锁读取、条件状态更新。

写方法均使用 READ_COMMITTED 本地事务；查询使用 REPEATABLE_READ 只读事务，让支付及冲正快照一致。所有写路径都先锁同一支付单，控制重复付款和并发冲正；外部HTTP调用不在这些事务中。

时间采用固定UTC+8、毫秒精度。首次创建和首次模拟付款要求 now < expires_at；恰好到期拒绝。重复创建、重复已成功付款即使到期仍返回原结果，不刷新期限、不重置通知进度。过期首次创建的候选插入会随异常回滚。

## 验证

先打包支付模块及依赖，再运行：

~~~powershell
mvn "-Dmaven.repo.local=$PWD/target/.m2" -pl ticket-payment-service -am package
.\deploy\verify\verify_payment.ps1 -VerifyNacos
~~~

验证需要 MySQL 和统一数据库凭证；带 VerifyNacos 时还需要本机127.0.0.1:8848和9848可用。不带此开关时只验证本地业务及HTTP。

已通过90项检查：20个并发创建请求只生成一张支付单，同键不同金额竞争只接受一个；重复/并发模拟成功与冲正；用户归属及默认开关；金额与到期精度、到期边界；SQL故障注入回滚；HTTP400/401/404/405/409和trace；服务重启读取原结果；真实Nacos在随机分组注册健康实例。

程序只创建随机 ticket_payment_verify_<UUID> 测试库并清理，Spring上下文退出时注销测试实例。不向正式ticket_payment写演示数据；正式库会在用户启动支付服务时创建和迁移。

## 下一阶段

订单的创建支付入口、可靠付款通知和付款依据已经接入，正常状态为 PAYMENT_CONFIRMING。库存确认、PAID及冲正恢复已接入，协调confirm/release竞争和迟到支付，见 [成交与冲正恢复](payment-fulfillment.md)。完成HTTP业务闭环后，再接入MQ + Outbox。
