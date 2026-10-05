# Event与Inventory内部服务身份

## 请求顺序

1. Order的`EventClient`或`InventoryClient`使用自己的随机服务凭证添加请求头，仍由LoadBalancer按服务名选择实例。
2. 接收端先选择`/internal/**`的安全链，过滤器常量时间比较凭证，成功后建立本次请求的`ticket-order-service`身份及`ROLE_ORDER_SERVICE`。
3. 安全链检查HTTP方法与路径；通过后才执行Controller → Service → Mapper。
4. 缺失或错误凭证返回401/UNAUTHORIZED；正确身份访问未授权路径或方法返回403/FORBIDDEN。

服务凭证确认调用方权限，业务响应校验确认返回事实，两者仍需要同时执行。HTTP超时、幂等、事务及恢复逻辑继续由现有业务代码处理；本次没有数据库迁移。

## 权限

| 接收服务 | 调用身份 | 允许的接口 |
| --- | --- | --- |
| Event | Order | GET `/internal/ticket-tiers/{id}/purchase-rule` |
| Inventory | Order | POST `/internal/stock-reservations`；POST `/{id}/confirm`与`/{id}/release`；GET `/internal/stock-reservations/{id}`；GET `/internal/stocks/{id}` |
| Inventory | Event | POST `/internal/stocks/initializations`；GET `/internal/stocks/initializations/{id}` |

库存初始化有优先匹配的独立安全链。Order凭证不能初始化库存，Event初始化凭证不能预留、确认或释放。用户和管理员JWT不能作为这些内部接口的服务身份。网关不公开这些路径。

## 本地凭证配置

| 方向 | 请求头 | 默认本地文件 | 可选环境变量 |
| --- | --- | --- | --- |
| Order → Event | X-Order-Event-Credential | `.local/service-credentials/order-event.token` | `ORDER_EVENT_SERVICE_TOKEN`或`ORDER_EVENT_CREDENTIAL_PATH` |
| Order → Inventory | X-Order-Inventory-Credential | `.local/service-credentials/order-inventory.token` | `ORDER_INVENTORY_SERVICE_TOKEN`或`ORDER_INVENTORY_CREDENTIAL_PATH` |

两个文件分别生成256位随机凭证，不覆盖已有文件，也不提交Git。启动时环境中的Token优先，否则读取文件。缺失或非64位小写十六进制值会使启动失败。接收端和发送端使用同一份对应凭证；凭证内容禁止打印、提交或返回用户。

IDEA重新加载Maven后，重启Event、Inventory与Order，工作目录为项目根目录。如从其他目录启动，使用上述路径环境变量设置绝对文件路径。现有Windows数据库环境变量继续使用。

本地第一版使用共享服务凭证，它代表持有凭证的调用方权限，不证明进程本身的身份。跨主机部署需TLS及受控凭证分发；轮换需协调双方重启。本次没有实现mTLS或自动轮换。

## 按顺序阅读

1. Order：`OrderJwtConfig`创建两种凭证对象。
2. Order：`EventClient`与`InventoryClient`的构造方法通过`defaultHeader`携带对应凭证。
3. Event：`EventSecurityConfig.internalEventSecurity`保护规则查询。
4. Inventory：`InitializationSecurityConfig`分别保护Event初始化与Order交易入口。
5. 两个接收服务：`ServiceCredentialFilter`建立本次请求的服务身份。
6. `ticket-security`中的`OrderEventCredential`、`OrderInventoryCredential`读取凭证并执行比较。

## 验证

`deploy/verify/verify_event_administration.ps1`增加匿名、用户/管理员JWT、错误和交叉凭证、越权HTTP方法、未知路径、初始化与交易身份隔离及被拒绝写请求不改变库存检查，并回归真实活动发布、下单、付款和出票。

`deploy/verify/verify_payment_fulfillment.ps1`回归确认、释放、响应丢失、临时不可用、重复通知、迟到支付及补偿。测试使用随机隔离数据库和临时端口；Nacos关闭，使用固定服务发现实例。没有在此重复验证Nacos注册或进行性能压测。

本次所有模块打包成功；活动管理与服务鉴权104项、支付履约137项检查通过。
