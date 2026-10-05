# 订单创建与库存恢复

## 当前完成的范围

本阶段实现订单数据库、正式下单与归属查询、固定参数的库存预留、持久化退避和多实例任务领取、订单到期后的幂等释放。订单预览和原内部库存演示接口保留。

每张订单只买一个票档，可以买多张。正式下单已按用户与场次累计限购，见 [累计限购](purchase-quota.md)；预览只检查本次数量。[支付服务](payment-service.md)已提供能力，订单可通过 [发起支付入口](order-payment-create.md)创建支付单，付款通知与依据保存见 [付款通知](payment-notification.md)，正常进入 PAYMENT_CONFIRMING 并停止原关闭任务；后续库存确认与关闭竞争处理见 [成交与冲正恢复](payment-fulfillment.md)，成交为PAID，随后本地事务出票为COMPLETED，见 [电子票生成与查询](ticket-issuance.md)；无法履约冲正为REVERSED。没有MQ或Outbox。

## 启动与手动使用

订单服务默认 8062，库存 8063，活动 8061，网关 8060。先启动 MySQL、Nacos、活动与库存服务，库存须已初始化相应的真实场次和票档。所有业务服务继承 Windows 的 LOCAL_MYSQL_USERNAME 和 LOCAL_MYSQL_PASSWORD。

订单 JDBC 默认自动创建 ticket_order，Flyway 创建：

- t_order：订单、金额、状态、购买幂等键、到期时间、恢复时间与任务租约。
- t_order_item：一单一个票档的名称、单价与数量快照。
- t_user_session_quota：同一用户、同一场次的累计占用额度；订单的quota_status记录是否已归还。
- flyway_schema_history：迁移执行历史。

真实Token与Redis会话认证已接入，见 [订单身份接入](order-auth.md)。默认不信任请求携带的用户编号；新下单和查询入口读取认证后的Principal。仅本地验证可设置 ORDER_DEV_IDENTITY_ENABLED=true 并重新启动订单服务，显式接受 X-Dev-User-Id。这不是生产认证；生产应关闭开发身份并接入真实认证及服务间认证。

下单示例（票档必须替换为实际 ID）：

~~~http
POST http://localhost:8060/api/orders
Content-Type: application/json
Authorization: Bearer <accessToken>

{"ticketTierId":3,"quantity":2,"idempotencyKey":"buy_001"}
~~~

同一用户重试保持同一幂等键、票档、数量；一次新的购买使用新的键。价格、订单编号和到期时间由服务端确定，重试读取数据库快照，不重算价格或期限。

查询：

~~~http
GET http://localhost:8060/api/orders/{orderNo}
Authorization: Bearer <accessToken>
~~~

成功结构仍为 Result<OrderDetailVO>。创建接口的 HTTP 202 表示订单已落库但库存处理尚未确定，客户端按订单编号查询或用原幂等键重复请求；HTTP 200 表示当前已有明确状态，需读取 data.status，CREATE_FAILED 也会返回原失败订单。字段校验 400，缺少身份 401，订单不存在或不归属当前用户 404，同键不同购买参数 409。库存预留发生在落库之后，未知结果由状态记录，不将超时等同于未扣库存。

## 代码阅读顺序

1. OrderController.create / getOrder：接收 DTO，解析身份，包装响应。
2. OrderIdentityResolver：服务端 Principal 优先，开发身份须显式开启。
3. OrderServiceImpl.create：查购买幂等键 → EventClient 查规则 → 校验开售和数量 → 计算金额。
4. OrderTransactionService.create：一个本地事务保存主表和订单项，写入 STOCK_PENDING。
5. OrderStockWorkflow.advance：原子领取任务；使用数据库中的订单编号、场次、票档、数量、到期时间调用 InventoryClient。
6. OrderMapper.finish：用领取令牌和前置状态更新结果，清理租约。
7. OrderStockReconcileJob：限量扫描到期任务，后台再次使用同一状态推进流程。

订单事务 Bean 与业务编排 Bean 分开，避免同类调用绕过事务代理。等待 HTTP 不占用订单数据库事务。预留记录和库存计数的本地事务仍由库存服务管理。

## 状态与故障处理

| 状态 | 含义 | 自动处理 |
| --- | --- | --- |
| STOCK_PENDING | 订单和购买快照已保存，库存结果未确定 | 使用原参数预留/核对 |
| PENDING_PAYMENT | 已确认 RESERVED，尚在支付窗口 | 截止时间到后进入关闭 |
| CREATE_FAILED | 已确认首次预留被拒绝，或过期后的原参数请求确认不能首次预留 | 不自动重建 |
| CLOSING | 已保存预留编号，正在释放 | 幂等释放，失败退避重试 |
| CLOSED | 已确认库存 RELEASED | 停止自动处理 |
| REVIEW_REQUIRED | 已售出、资源异常或状态不符合当前阶段 | 保留原因并输出错误日志，人工核对 |

首次预留明确拒绝时记录 CREATE_FAILED。若之前已经有不确定调用，尚未到期的后续拒绝仍保留 STOCK_PENDING，避免迟到请求随后预留成功造成遗留库存。到期后仍沿用原到期时间调用：已有预留会返回原状态；没有预留则库存首次预留校验拒绝，不生成新的期限。异常冲突或资源缺失仍保留 REVIEW_REQUIRED。

库存返回 RESERVED 时：
- 支付窗口未结束，保存预留 ID，转为 PENDING_PAYMENT。
- 已到期，先持久化 CLOSING 和预留 ID，再请求释放，确认后 CLOSED。

库存返回 SOLD 时不能推断支付已成功，转 REVIEW_REQUIRED，不释放售出库存。RELEASED 返回 CLOSED。关闭时发生业务冲突也转 REVIEW_REQUIRED，不能把释放失败写成关闭成功。

## 持久化恢复与多实例

next_attempt_at、attempt_count、last_error 保存重试进度。指数退避默认 5 秒至 5 分钟，不在请求线程 sleep。任务扫描使用状态和时间索引，每轮最多 20 条。

claim 的条件更新领取 lease_token，记录 lease_until。进程退出后租约到期可重新领取；完成 SQL 必须同时匹配前置状态和当前令牌，旧进程不能覆盖新领取者的进度。租约默认 60 秒，配置校验要求覆盖两次库存及两次支付 HTTP 超时并留出 5 秒余量。库存幂等仍是必要保护，租约本身不能取消迟到的 HTTP 请求。

后台每张订单生成独立 traceId 并清理 MDC，日志包含 orderNo；跨服务 Client 传播 traceId。REVIEW_REQUIRED 输出错误日志。运维告警平台及人工恢复接口按后续阶段接入，不能宣称已完成自动处理所有异常。

## 配置

| 配置 / 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| ORDER_DB_URL | 本机 ticket_order | 订单服务自己的数据库 |
| ORDER_DEV_IDENTITY_ENABLED | false | 本地显式开发身份开关 |
| ORDER_PAYMENT_WINDOW | 15m | 固定支付窗口 |
| ORDER_RECOVERY_ENABLED | true | 后台恢复与到期任务开关 |
| ORDER_RECOVERY_DELAY_MS | 5000 | 每轮完成后的等待毫秒数 |
| ticket.order.batch-size | 20 | 每轮最大条数，1 到 100 |
| ticket.order.lease-duration | 60s | 多实例领取租约 |
| ticket.order.retry-base / retry-max | 5s / 5m | 持久化指数退避 |

关闭恢复任务后，处理中订单和到期订单不会自动推进；重新开启后读取原数据库记录继续处理。

网关订单路由响应超时默认为 30000 毫秒，可由 ORDER_GATEWAY_RESPONSE_TIMEOUT_MS 覆盖，为串行规则查询、库存预留及临近到期释放留出时间。客户端或网关超时仍应使用原购买幂等键核对，不代表下单事务未完成。

## 可复现验证

先打包订单与库存：

~~~powershell
mvn "-Dmaven.repo.local=$PWD/target/.m2" -pl 'ticket-order-service,ticket-inventory-service' -am package
& ./deploy/verify/verify_order_workflow.ps1
~~~

验证需要本机 MySQL 和统一凭证，使用随机命名的两个独立测试库、独立服务进程和端口；结束后删除自身测试库并停止自身进程。活动 HTTP 桩提供稳定规则，库存代理在真实库存提交后注入响应超时。本轮验证使用 SimpleDiscoveryClient 的固定测试实例，生产配置仍使用 Nacos；该验证不重复测试已有 Nacos 注册能力。

覆盖正常下单、价格快照、身份开关、归属隔离、双实例同键并发、幂等参数冲突、库存不足、订单项失败事务回滚、库存响应丢失、重启恢复、租约领取、并发不超卖、到期释放、释放响应丢失、过期未知预留恢复、未到达库存的过期请求、已售库存保护。属于正确性与故障恢复验证，不作为吞吐量压测结果。

本阶段实测通过 75 项流程检查；原 HTTP 客户端隔离验证 153 项检查继续通过。两个随机测试库及自身验证进程已清理。正式 ticket_order 库由用户下一次启动订单服务时自动创建和迁移，本次没有向业务库写入演示订单。

## 后续接入 MQ 的位置

订单状态机与库存幂等会继续保留。支付HTTP创建、可靠通知与依据保存已接入，库存确认和无法履约冲正恢复见 [成交与冲正恢复](payment-fulfillment.md)；后续增加Outbox、可靠消息发布和幂等消费。到期消息可以触发同一推进逻辑，数据库核对任务负责补漏。当前代码没有MQ发布或消费，不能将本阶段描述为完整支付Saga。
