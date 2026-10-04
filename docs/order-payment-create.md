# 订单发起支付：Order -> Payment

## 当前实现

新增 POST /api/orders/{orderNo}/payments，当前用户只提供订单编号。Order从本地订单表读取用户、总金额和原到期时间，通过PaymentClient创建或取回同一张支付单。

此步完成创建支付单的跨服务调用，不修改订单状态，不新增订单表字段。支付单SUCCESS与订单PAID是不同事实；后续的 [付款通知](payment-notification.md) 已接入通知与依据保存，正常订单进入 PAYMENT_CONFIRMING。库存确认与迟到支付冲正恢复已在 [成交与冲正恢复](payment-fulfillment.md) 接入；创建支付单本身仍不代表付款或履约完成。

## 调用顺序

1. OrderController.createPayment：读取OrderIdentityResolver提供的当前用户。
2. OrderServiceImpl.createPayment：根据用户和订单编号查询归属，不存在或不归属统一404。
3. requirePayable：仅允许PENDING_PAYMENT、有效预留编号、当前时间早于expires_at。
4. PaymentCreateRequest：从已保存订单读取orderNo、userId、totalAmount、expiresAt。
5. PaymentClient.createPayment：支付专用LoadBalanced Builder，按服务名发POST /internal/payments。
6. 支付Controller -> PaymentServiceImpl -> 支付库：唯一order_no控制创建幂等。
7. PaymentClient：解析Result<PaymentResponse>，检查状态组合和与请求的快照一致性。
8. Order再次查询归属并检查状态、期限，避免HTTP期间已进入关闭还返回“可发起支付”。
9. 返回Result<OrderPaymentVO>，订单保持原状态。

HTTP等待没有订单数据库事务或长时间行锁。前后检查不能替代下一阶段支付/关闭竞争处理，也不能阻止随后发生的关闭；支付期限、库存互斥终态和支付证据的完整协调需要后续接入。

## 手动调用

MySQL、Nacos、Order和Payment先就绪；重启订单服务加载PaymentClient。默认支付服务名ticket-payment-service，Order不依赖Payment的Maven模块，也不直接读支付库。

Auth未接入时，仅本地演示在Order的IDEA运行配置中设置ORDER_DEV_IDENTITY_ENABLED=true。创建支付单无需开启Payment的模拟开关；只有模拟付款或冲正需要PAYMENT_SIMULATION_ENABLED=true，公共支付接口开发身份另需PAYMENT_DEV_IDENTITY_ENABLED=true。

先通过POST /api/orders取得当前用户的未到期待支付订单，然后调用：

~~~powershell
$orderNo = '实际创建返回的32位订单编号'
Invoke-RestMethod -Method Post -Uri "http://localhost:8060/api/orders/$orderNo/payments" -Headers @{ 'X-Dev-User-Id' = '1' }
~~~

直接订单端口也可使用http://localhost:8062。请求不需要JSON正文；传入amount或userId不会覆盖订单快照。

响应示例：

~~~json
{
  "code": "OK",
  "message": "success",
  "data": {
    "orderNo": "原订单编号",
    "paymentNo": "支付服务生成的编号",
    "amount": 398.00,
    "paymentStatus": "CREATED",
    "expiresAt": "原订单到期时间",
    "paidAt": null,
    "reversalNo": null,
    "reversalStatus": null
  },
  "traceId": "追踪编号"
}
~~~

已有支付单若SUCCESS或已有冲正结果，原创建请求可以返回它的现有事实，不能伪装成新的CREATED；当前不会据此把订单置PAID。

## 异常与重试

| 情况 | HTTP / code | 行为 |
| --- | --- | --- |
| 没有可信用户身份 | 401 / UNAUTHORIZED | 不调用支付 |
| 订单不存在或属于其他用户 | 404 / RESOURCE_NOT_FOUND | 不泄露其他用户订单 |
| 订单状态不允许、到期、预留编号无效 | 409 / CONFLICT | 不调用支付 |
| 支付返回合法参数拒绝 | 400 / BAD_REQUEST | 核对原订单快照 |
| 支付到期或同一订单参数冲突 | 409 / CONFLICT | 不覆盖原支付单 |
| 无服务实例、连接失败、支付5xx | 503 / UPSTREAM_UNAVAILABLE | 不修改订单，原订单编号核对或重试 |
| 连接或响应正文超时 | 504 / UPSTREAM_TIMEOUT | 支付单可能已经创建，不能认定未执行 |
| 错误业务码、重定向、其他状态或JSON/快照错误 | 502 / INVALID_UPSTREAM_RESPONSE | 拒绝不符合契约的响应 |

支付创建接口没有合法404业务结果，因此PaymentClient把上游404视为接口契约异常502；不将其误当作本地订单不存在。

Client不自动重试POST，不跟随重定向。用户在订单仍可支付时以原订单编号重新调用，发送原用户、金额和期限，Payment依靠唯一order_no返回原payment_no。Order没有为创建支付单增加后台自动重试任务。

如果远程创建成功但本地后置检查发现CLOSING或过期，返回409，保留已经创建的支付记录，不删除对方数据，也不回退关闭状态。

## 客户端配置

| 环境变量 | 默认值 |
| --- | --- |
| PAYMENT_CLIENT_CONNECT_TIMEOUT | 2s |
| PAYMENT_CLIENT_READ_TIMEOUT | 5s |

ticket.clients.payment.service-id默认ticket-payment-service，可在启动参数覆盖；配置校验服务名格式和正数超时。paymentRestClientBuilder、eventRestClientBuilder、inventoryRestClientBuilder分别通过Qualifier注入，复用JSON配置和ReadTimeoutRequestFactory。TraceIdContext的X-Trace-Id继续传播到支付服务。

## 验证

~~~powershell
mvn "-Dmaven.repo.local=$PWD/target/.m2" -pl ticket-order-service,ticket-payment-service -am package
.\deploy\verify\verify_order_payment.ps1
.\deploy\verify\verify_inventory_client.ps1
~~~

订单到支付验证通过123项检查：真实MySQL、独立Order和Payment进程，12个并发发起支付仅一张支付单；身份、归属、状态、期限和预留检查；用户金额不可覆盖快照；trace；合法400/409、上游5xx、头部/正文超时、重定向、错误JSON/业务码/业务字段；不自动重试；真实支付提交后丢失响应，原订单请求取回原支付单；HTTP期间订单转CLOSING的后置检查；返回已有SUCCESS与冲正事实，订单不误置PAID；无实例503。

本轮使用Spring SimpleDiscoveryClient和真实LoadBalancer选择固定测试实例，支付响应故障代理只用于注入故障；Nacos注册能力在支付独立阶段已经通过验证。本轮不重新测试Nacos或库存履约。订单为本测试在随机库中创建的已预留状态夹具，没有实际扣库存。

程序清理自身两个随机库和测试进程，不写默认业务库。原库存/活动客户端153项回归检查通过。

## 下一步

Payment可靠通知Order及付款依据保存已完成，见 [付款通知](payment-notification.md)。库存确认、支付与关闭竞争、无法履约冲正已接入，见 [成交与冲正恢复](payment-fulfillment.md)。完成第一版HTTP闭环后接入MQ + Outbox。
