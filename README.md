# 分布式票务交易平台

基于 Spring Cloud 的学习项目。第一版聚焦按票档抢票，验证微服务边界、高并发库存控制和跨服务交易一致性。

项目设计与实施顺序见 [设计文档](docs/design.md)。当前包含 `ticket-common`、网关、活动服务、订单服务、库存服务、支付服务和Auth服务。活动提供查询与购票规则；订单提供落库、购买幂等、查询、库存预留与后台恢复、未支付到期释放，并通过PaymentClient创建支付单；库存提供预留、确认售出与释放；支付提供支付单、模拟成功、查询及全额冲正。支付可靠通知订单，订单回查并保存付款依据，正常进入 PAYMENT_CONFIRMING，后台确认库存后成交为 PAID；库存已释放则恢复全额模拟冲正，最终 REVERSED。订单成交后本地事务生成每张电子票并进入 COMPLETED，提供本人电子票查询。Auth已完成注册登录与会话管理，网关及业务服务身份接入、累计限购、验票、Outbox/MQ按后续步骤实现。代码阅读见 [订单创建与恢复](docs/order-workflow.md)、[支付服务](docs/payment-service.md)、[订单发起支付](docs/order-payment-create.md)、[付款通知](docs/payment-notification.md)、[成交与冲正恢复](docs/payment-fulfillment.md)、[电子票生成与查询](docs/ticket-issuance.md)、[Auth服务](docs/auth-service.md)。

## Auth 服务当前阶段

新增 `ticket-auth-service`，默认8065：注册、登录、Refresh凭证原子轮换、当前会话退出；MySQL/Flyway建立用户表，BCrypt保存密码摘要，RSA签发JWT，Redis管理会话。Auth自身已实现，网关、订单、支付的Token接入尚待下一步。启动前需本地Redis与持久化RSA密钥，运行类为 `com.byy.ticket.auth.TicketAuthApplication`，启动步骤及代码阅读见 [Auth服务](docs/auth-service.md)。验证脚本为 `deploy/verify/verify_auth.ps1`，只使用随机测试数据库和Redis前缀。

## 订单下单与恢复

正式入口为 `POST /api/orders` 和 `GET /api/orders/{orderNo}`。创建请求为 `ticketTierId`、`quantity`、`idempotencyKey`。订单服务先校验活动规则，在本地事务中保存主表和购买快照，再在事务外调用库存；不确定结果保留 `STOCK_PENDING`，后台沿用原参数核对。预留成功进入待支付；未支付到期订单先进入 `CLOSING`，确认释放后 `CLOSED`。库存已售出等异常终态进入 `REVIEW_REQUIRED`，不自动释放。

Auth 尚未接入，新入口默认需要可信身份。仅本地学习可设置 `ORDER_DEV_IDENTITY_ENABLED=true`，重新启动后在请求中传 `X-Dev-User-Id: 1`。用户身份不放入下单 JSON。开发身份不是生产认证。完整请求、状态解释、事务边界、租约与重试配置见 [订单创建与恢复](docs/order-workflow.md)。

本阶段的故障验证脚本为 `deploy/verify/verify_order_workflow.ps1`：使用真实 MySQL、独立订单/库存进程、活动 HTTP 桩和库存响应故障代理，验证幂等、并发、事务回滚、响应丢失、服务重启和到期释放。测试只使用随机测试库，结束后清理自身库和进程。

本阶段通过 75 项下单流程检查及原客户端 153 项回归检查；正式订单库会在下一次启动订单服务时迁移，未写业务演示订单。网关订单路由超时调整为 30 秒，覆盖串行依赖调用的等待时间。

## 支付服务当前阶段

`ticket-payment-service` 默认端口 `8064`，沿用 `LOCAL_MYSQL_USERNAME`、`LOCAL_MYSQL_PASSWORD`，首次启动自动创建 `ticket_payment`；Flyway 建立 `t_payment`、`t_payment_reversal` 及迁移历史表。运行类为 `com.byy.ticket.payment.TicketPaymentApplication`。

默认模拟成功和冲正关闭。仅本地演示在 Payment 运行配置中设置 `PAYMENT_SIMULATION_ENABLED=true;PAYMENT_DEV_IDENTITY_ENABLED=true`，公共接口带 `X-Dev-User-Id: 1`。创建单、查询和冲正由内部接口提供；网关只路由 `/api/payments/**`，不转发内部路径。支付成功和待通知状态原子保存，后台可靠通知订单并恢复失败任务；订单保存依据后进入 PAYMENT_CONFIRMING，核对原库存后成交或冲正，矛盾事实进入 REVIEW_REQUIRED。DELIVERED 只表示依据已接收，成交及冲正进度分别由 PAID、REVERSED 表示。完整接口和阅读顺序见 [支付服务](docs/payment-service.md)、[付款通知](docs/payment-notification.md)、[成交与冲正恢复](docs/payment-fulfillment.md)、[电子票生成与查询](docs/ticket-issuance.md)。

支付模块通过90项真实MySQL、并发、事务回滚、HTTP、重启与Nacos注册检查。验证脚本为 `deploy/verify/verify_payment.ps1 -VerifyNacos`，只使用并清理随机测试库；正式支付库由用户启动时迁移。

订单已新增 `POST /api/orders/{orderNo}/payments`：检查归属、状态、预留和期限，读取原订单金额后经HTTP创建或返回原支付单。创建支付单本身不更新订单为已支付。通知与付款依据见 [付款通知](docs/payment-notification.md)，验证脚本为 `deploy/verify/verify_payment_notification.ps1`。库存确认与冲正恢复已接入，追加出票后通过135项真实三服务检查，脚本为 `deploy/verify/verify_payment_fulfillment.ps1`。MQ + Outbox按后续学习阶段引入。

## 电子票生成与查询

电子票已新增 `GET /api/orders/{orderNo}/tickets`，返回本人订单状态与独立票号列表。重启订单服务执行 Flyway V4：新增订单库的 `t_ticket`；已有 `PAID` 订单由原后台任务恢复出票。全部插票与 `COMPLETED` 状态同事务提交，唯一键与领取令牌防止重复出票。专项验证脚本 `deploy/verify/verify_ticket_issuance.ps1` 已通过65项检查；代码阅读见 [电子票生成与查询](docs/ticket-issuance.md)。目前不提供二维码或入场核验。

## 第一版目标

用户能浏览活动与场次，在开售后购买指定票档，完成模拟支付并取得电子票；未支付订单到期后自动取消并释放库存。管理员能维护活动、场次、票档与库存。

重点验收：并发下不超卖、同一用户不能重复购买同一场次、重复请求和消息不会重复扣库存或出票、失败和超时后资源最终得到正确处理。

## 当前工程与启动

父工程负责聚合和依赖版本管理，网关、活动、订单、库存和支付服务各自拥有启动类、配置和可执行 JAR；`ticket-common` 是公共代码库，不单独启动。Gateway 使用 WebFlux/Netty，业务服务使用 Spring MVC/Tomcat。

要求 JDK 17 或更新版本、Maven 3.9。先在项目根目录构建（命令中的本地仓库与本项目验证使用的仓库一致）：

```powershell
# 本机已安装的 JDK 17；其他机器改为实际 JDK 路径。
$env:JAVA_HOME = 'C:\Users\RE\.jdks\ms-17.0.20'
mvn "-Dmaven.repo.local=$PWD/target/.m2" package
```

先按 [Nacos 3.x 官方入门文档](https://nacos.io/docs/v3.1/quickstart/quick-start/) 启动单机 Nacos。五个应用默认连接 `127.0.0.1:8848`，客户端还需要能访问其 gRPC 端口 `9848`。Nacos 3.x 的控制台通常位于 `http://localhost:8080/index.html`，不是应用配置中的服务地址。当前代码只接入注册发现，还没有接入配置中心。

若 Nacos 地址不同，在启动各应用的窗口中设置相同的 `NACOS_SERVER_ADDR`。若开启了 Nacos 客户端鉴权，还需设置 `SPRING_CLOUD_NACOS_DISCOVERY_USERNAME` 和 `SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD`，使用该 Nacos 实例的账号密码。

活动服务启动前还需完成下面的数据库准备。Nacos 和数据库就绪后，在两个 PowerShell 窗口中分别从项目根目录启动：

```powershell
& 'C:\Users\RE\.jdks\ms-17.0.20\bin\java.exe' -jar ticket-event-service/target/ticket-event-service-1.0-SNAPSHOT.jar
```

```powershell
& 'C:\Users\RE\.jdks\ms-17.0.20\bin\java.exe' -jar ticket-gateway/target/ticket-gateway-1.0-SNAPSHOT.jar
```

先在 Nacos 控制台确认 `ticket-event-service` 和 `ticket-gateway` 注册成功。查询活动服务：`http://localhost:8061/api/events/ping`。通过网关查询：`http://localhost:8060/api/events/ping`。两者都应返回：

```json
{"service":"ticket-event-service","status":"ok"}
```

网关按 `/api/events/**` 匹配请求，保留原路径；通过 `lb://ticket-event-service` 从 Nacos 发现实例并进行负载均衡。需要换端口时使用 `EVENT_PORT`、`GATEWAY_PORT`，活动服务重新注册实际端口，网关不再配置它的固定地址。

IntelliJ IDEA 可重新加载根 Maven 工程，将 Project SDK 和 Maven Runner JRE 设为 JDK 17，分别运行 `TicketEventApplication`、`TicketGatewayApplication`、`TicketOrderApplication`、`TicketInventoryApplication`、`TicketPaymentApplication`。停止命令行服务使用各窗口的 Ctrl+C。

### 订单服务

订单服务默认端口为 `8062`，可通过 `ORDER_PORT` 覆盖。已引入 MyBatis-Plus、MySQL 与 Flyway，启动时使用统一的 `LOCAL_MYSQL_USERNAME`、`LOCAL_MYSQL_PASSWORD`，自动创建 `ticket_order` 库及订单主表、订单项、Flyway 历史表。`ORDER_DB_URL` 可覆盖连接地址；默认时间为固定东八区。Web、RestClient、Nacos、LoadBalancer 和 `ticket-common` 继续使用。

```powershell
& 'C:\Users\RE\.jdks\ms-17.0.20\bin\java.exe' -jar ticket-order-service/target/ticket-order-service-1.0-SNAPSHOT.jar
```

在 IDEA 中运行 `com.byy.ticket.order.TicketOrderApplication`，模块选择 `ticket-order-service`。访问 `http://localhost:8062/api/orders/ping` 应返回：

```json
{"service":"ticket-order-service","status":"ok"}
```

Nacos 服务列表应出现健康的 `ticket-order-service` 实例。网关通过 `lb://ticket-order-service` 转发 `/api/orders/**`，订单服务通过支持负载均衡的 RestClient 调用活动服务，购票预览用法见下文。

## 活动数据库与 Flyway

使用 MySQL 8.0.16 或更新的 8.0 版本，并确保 MySQL 已启动。默认连接地址包含 `createDatabaseIfNotExist=true`，活动服务首次连接时会自动创建不存在的 `ticket_event` 库，连接账号需要有创建数据库的权限。也可提前执行 [空库初始化脚本](deploy/mysql/create_event_database.sql)。本地数据库凭证统一通过以下环境变量提供，可设置在 Windows 用户环境变量中：

| 环境变量 | 含义 |
| --- | --- |
| `LOCAL_MYSQL_USERNAME` | 数据库账号，必须配置，例如 `root`；需要该库访问和建表权限，自动建库时还需要创建数据库权限 |
| `LOCAL_MYSQL_PASSWORD` | 该账号的密码，必须配置；没有密码的本地账号也需显式配置为空 |
| `EVENT_DB_URL` | 可选；默认连接 `127.0.0.1:3306/ticket_event`，自动建库并统一会话时区为固定东八区 `+08:00`（URL 中写作 `%2B08:00`，不依赖 MySQL 命名时区表）；自定义地址会覆盖整个默认地址，需自行包含自动建库及时区参数 |

设置 Windows 用户环境变量后，彻底退出并重新打开 IDEA，各服务可继承这套本地凭证，无需在每份运行配置中重复填写。项目仍分别配置各自的数据库地址和库名。Run → Edit Configurations → Environment variables 中的同名变量会覆盖继承的值，可删除以统一使用 Windows 中的值；旧的 `EVENT_DB_USERNAME`、`EVENT_DB_PASSWORD` 已不再引用，也可删除。

也可在 PowerShell 中通过交互输入临时设置本窗口的环境变量：

```powershell
$env:LOCAL_MYSQL_USERNAME = Read-Host 'MySQL username'
$dbCredential = Get-Credential -UserName $env:LOCAL_MYSQL_USERNAME -Message 'Enter MySQL password'
$env:LOCAL_MYSQL_PASSWORD = $dbCredential.GetNetworkCredential().Password
```

密码只通过运行环境提供，不写入版本控制。若使用上述 PowerShell 临时变量，需在同一终端启动活动服务，已经打开的 IDEA 不会继承该终端后来设置的变量。

活动服务启动时扫描 `src/main/resources/db/migration`，执行 `V1__create_event_tables.sql` 并自动创建 `flyway_schema_history`。迁移只建立活动、场次和票档三张表，包含关联外键、票档名称唯一约束、状态/价格/时间等 CHECK 约束及查询索引；库存表在库存服务中设计。

时间字段按固定东八区 `+08:00` 保存，金额为 `DECIMAL(10,2)`，主键使用 `BIGINT AUTO_INCREMENT`。开售状态根据时间计算，不单独保存。场次停售时间不得晚于演出开始时间。已经执行的迁移文件不再修改，后续调整新增 `V2__...sql` 等版本。

启动后在 `ticket_event` 查询 `SHOW TABLES` 和 `SELECT version, description, success FROM flyway_schema_history`，核对迁移是否完成。MySQL 驱动负责自动建库，Flyway 负责执行建表及后续迁移；对已有非空库不自动执行 baseline。

## 活动查询

查询沿用电表项目的 Controller → Service 接口 → ServiceImpl → Mapper 分层，使用 MyBatis-Plus 3.5.17、`BaseMapper`、`LambdaQueryWrapper` 和分页插件访问 MySQL。请求参数使用 DTO 和 Jakarta Validation，业务实体放在活动服务的 `model` 包，响应使用 `vo` 包下的 record。`ticket-common` 仅保存通用响应、分页结构、基础异常和 traceId 工具，不保存业务实体和 Mapper。Spring Boot 4 使用专用的 `mybatis-plus-spring-boot4-starter`，见 [官方安装说明](https://baomidou.com/getting-started/install/)。

活动查询统一返回 `Result<T>`，字段为 `code`、`message`、`data`、`traceId`；成功 `code` 为 `OK`。异常由 `GlobalExceptionHandler` 转换成相同结构，并保留 HTTP 400、404、500 等状态。响应头 `X-Trace-Id` 与响应体一致。

| 接口 | 功能 |
| --- | --- |
| `GET /api/events?page=1&pageSize=20` | 已发布活动列表，`data` 返回 `page`、`pageSize`、`total`、`records`；按创建时间和 ID 倒序排列 |
| `GET /api/events/{eventId}` | 已发布活动详情 |
| `GET /api/events/{eventId}/sessions` | 已发布场次及启用票档；场次按开始时间、ID 排列，票档按价格、ID 排列 |

`page` 从 1 开始，默认 1；`pageSize` 为 1～100，默认 20，与电表项目保持一致，替换此前的 `size` 参数。非法参数返回 HTTP 400；字段校验错误在 `data` 中返回字段与错误说明。不存在或未发布的活动返回 HTTP 404。活动没有场次时 `data` 返回 `[]`；场次没有启用票档时保留场次，`ticketTiers` 返回 `[]`。票档按场次 ID 批量查询，避免逐场查询。启用票档表示可展示，不代表有库存或当前可购买。

空列表响应示例：

```json
{
  "code": "OK",
  "message": "success",
  "data": {"records": [], "total": 0, "page": 1, "pageSize": 20},
  "traceId": "本次请求的32位十六进制追踪标识"
}
```

建表不会自动添加业务数据，空库查询返回 `total: 0` 和空列表。可在本地 SQL 控制台手动执行一次 [演示数据脚本](deploy/mysql/seed_event_demo.sql)，获得脚本返回的活动 ID 后查询详情和场次。演示脚本不属于 Flyway，重复执行会添加重复演示数据。

重新启动活动服务和网关后，通过网关访问 `http://localhost:8060/api/events`，详情和场次 URL 中使用实际活动 ID。也可直接通过 `http://localhost:8061/api/events` 查询活动服务。

### 内部购票规则查询

活动服务提供 `GET /internal/ticket-tiers/{ticketTierId}/purchase-rule`，为后续订单服务调用准备。返回 `Result<TicketPurchaseRuleVO>`，包含 `eventId`、`sessionId`、`ticketTierId`、`ticketTierName`、`price`、`saleStartTime`、`saleEndTime`、`purchaseLimit`。票价使用 `BigDecimal`，开售时间按固定东八区解释。

通过现有 Mapper 查询票档、场次和活动，要求票档已启用、场次及活动已发布。票档 ID 非正整数或类型不正确返回 HTTP 400；资源不存在、票档禁用、场次未发布或取消、活动未发布或下线返回 HTTP 404。规则查询不检查当前是否开售，不预留库存，也不计算累计限购；这些校验与操作按后续业务步骤实现。

重启活动服务后，直接访问 `http://localhost:8061/internal/ticket-tiers/{实际票档ID}/purchase-rule` 验证。使用演示数据时，可先在 `t_ticket_tier` 查询实际主键。现有网关路由只匹配 `/api/events/**`，不会转发该内部路径。`/internal` 只是接口用途约定，当前尚未增加服务身份校验，因此不代表通过活动服务端口访问时已经受到鉴权保护。

## 订单预览与服务间调用

启动 Nacos、活动服务、订单服务和网关后，向 `http://localhost:8060/api/orders/preview` 发送 POST 请求，Content-Type 为 `application/json`：

```json
{"ticketTierId": 3, "quantity": 2}
```

将 `3` 替换为实际启用票档 ID。`OrderController` 校验请求 DTO，调用 `OrderServiceImpl`；后者通过 `EventClient` 获取规则，按固定东八区校验开售时间及本次购买数量，使用 BigDecimal 计算金额，返回 `Result<OrderPreviewVO>`。开售区间为 `[saleStartTime, saleEndTime)`。若单价为 199.00 元、购买两张，`totalAmount` 为 398.00。预览不创建订单、不预留库存，也不检查用户累计已购数量；后续实际下单需要重新校验规则并接入库存及累计限购。

`RestClientConfig` 创建标记为 `@LoadBalanced` 的 RestClient.Builder，保留 Boot 的 JSON 转换器，配置 JDK HTTP 连接及读取超时。`EventClient` 使用 `http://ticket-event-service/internal/ticket-tiers/{id}/purchase-rule`，由 DiscoveryClient 和 LoadBalancer 查找并选择实例；HTTP 请求直接发送到活动服务，不经过网关或 Nacos 服务器代理。订单服务只定义自己的 `TicketPurchaseRuleResponse` 契约，不依赖活动服务 Maven 模块或数据库。用法参见 [Spring Cloud 官方说明](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/common-abstractions.html#spring-restclient-as-a-loadbalancer-client)。

调用配置位于订单服务的 `ticket.clients.event`：`service-id` 默认 `ticket-event-service`，连接超时默认 2 秒，读取超时默认 5 秒，分别可用 `EVENT_CLIENT_CONNECT_TIMEOUT`、`EVENT_CLIENT_READ_TIMEOUT` 覆盖。当前不启用自动重试。网关订单路由的响应超时为 8 秒，给订单服务返回依赖超时错误留出时间；这些 HTTP 超时不等同于服务发现等所有处理步骤的总时限。

| 场景 | HTTP 状态 / code |
| --- | --- |
| 非法参数、未开售、已停售、本次数量超限 | 400 / BAD_REQUEST |
| 票档不存在、禁用或所属资源未发布 | 404 / RESOURCE_NOT_FOUND |
| 找不到实例、连接失败、活动服务 5xx | 503 / UPSTREAM_UNAVAILABLE |
| 活动服务调用超时 | 504 / UPSTREAM_TIMEOUT |
| 响应无法解析、规则缺失、返回了其他票档规则 | 502 / INVALID_UPSTREAM_RESPONSE |

订单请求的 traceId 通过 `X-Trace-Id` 传给活动服务，便于关联两个服务的响应及日志。已验证两个发现实例的负载均衡、开售边界、金额计算、参数校验、各类调用故障，以及独立测试端口与 Nacos 分组下的 Gateway → Order → Event → MySQL 链路；临时验证实例和数据库记录已清理。

## 库存服务

### 启动与建表

库存服务模块为 `ticket-inventory-service`，启动类为 `com.byy.ticket.inventory.TicketInventoryApplication`，默认端口 `8063`（可用 `INVENTORY_PORT` 覆盖）。在 IDEA 重新加载 Maven 后运行该启动类即可，沿用 Windows 中的 `LOCAL_MYSQL_USERNAME`、`LOCAL_MYSQL_PASSWORD`，无需重复填写凭证。

默认连接 `ticket_inventory` 库并自动建库，Flyway 执行本模块的 `V1__create_inventory_tables.sql`。可用 `INVENTORY_DB_URL` 覆盖完整连接地址；账号权限、MySQL 版本和东八区设置与活动服务相同。库存服务只连接自己的库。

```powershell
mvn "-Dmaven.repo.local=$PWD/target/.m2" -pl ticket-inventory-service -am package
& 'C:\Users\RE\.jdks\ms-17.0.20\bin\java.exe' -jar ticket-inventory-service/target/ticket-inventory-service-1.0-SNAPSHOT.jar
```

| 表 | 作用 |
| --- | --- |
| `t_ticket_stock` | 一个票档一行：所属场次、总量、可用量、预留量、售出量 |
| `t_stock_reservation` | 一个订单关联编号一行：预留 ID、场次、票档、数量、到期时间和状态 |

库存数量必须非负，且 `total_quantity = available_quantity + reserved_quantity + sold_quantity`。票档和场次 ID 不跨库建立外键。首次建表没有业务库存，先在活动服务查询真实票档和场次 ID，再填写 [库存演示脚本](deploy/mysql/seed_inventory_demo.sql) 中的两个 NULL 并手动执行。重复执行脚本不会重置库存。

### 内部接口

直接访问库存服务端口；当前网关未转发这些路径，内部接口的服务身份认证在后续阶段加入。

| 接口 | 作用 |
| --- | --- |
| `POST /internal/stock-reservations` | 预留库存 |
| `POST /internal/stock-reservations/{reservationId}/confirm` | 预留转为售出 |
| `POST /internal/stock-reservations/{reservationId}/release` | 释放预留，归还可用量 |
| `GET /internal/stock-reservations/{reservationId}` | 查询预留当前状态 |
| `GET /internal/stocks/{ticketTierId}` | 查询票档库存数量 |

预留请求示例（ID 换成实际值，时间换成晚于当前时间的东八区时间，精度最多毫秒）：

```json
{
  "orderId": "DEMO_ORDER_001",
  "sessionId": 1,
  "ticketTierId": 3,
  "quantity": 2,
  "expiresAt": "2030-01-01T12:15:00.000"
}
```

响应仍是 `Result<StockReservationVO>`，成功时 `data` 含 `reservationId`、`orderId`、场次、票档、数量、到期时间和 `status`。确认和释放用返回的 32 位 `reservationId`，无需请求体。订单编号限 1～64 位字母、数字、下划线或短横线；区分大小写。第一版一个订单只购买一个票档。

同一订单编号重试时所有参数必须一致，包括到期时间；后续订单服务应在第一次调用前确定编号和时间，重试时复用。已到期的相同请求仍返回原记录，已释放的编号不能重新预留，返回的状态可能是 `SOLD` 或 `RELEASED`。

### 核心代码流程

`InternalStockReservationController` → `InventoryServiceImpl` → 两个 Mapper → MySQL。预留方法带 `@Transactional`：

1. 尝试插入预留记录；`order_id` 唯一键冲突时保留并锁定原记录。
2. 若是重试，核对原参数后返回原记录，不修改库存。
3. 若是首次请求，用一条带 `available_quantity >= quantity` 条件的 SQL 扣可用量、加预留量。
4. 库存不足等异常使预留记录和数量修改一起回滚；成功一起提交。

例如库存 10 张，预留 2 张后为“可用 8、预留 2、售出 0”。确认后为“可用 8、预留 0、售出 2”；若改为释放，则为“可用 10、预留 0、售出 0”。同一预留只能从 `RESERVED` 转为 `SOLD` 或 `RELEASED`，终态不能互换。

确认和释放先用 `SELECT ... FOR UPDATE` 锁定预留，再修改状态和库存。重复确认已售记录、重复释放已释放记录均返回成功且不再次改变数量；相反方向的操作返回冲突。MySQL 的锁与条件更新在多个服务实例下也生效，无需 Java `synchronized`。

| 场景 | HTTP / code |
| --- | --- |
| 非法参数、首次预留时间已过 | 400 / BAD_REQUEST |
| 库存或预留不存在 | 404 / RESOURCE_NOT_FOUND |
| 库存不足、场次不匹配、同编号参数不同、终态冲突 | 409 / CONFLICT |
| 数据库锁竞争或临时访问故障 | 503 / SERVICE_BUSY |
| 非预期错误、记录与计数不一致 | 500 / INTERNAL_ERROR |

库存服务自身不按到期时间自动释放。正式创建的订单由订单服务核对状态并协调到期释放；原内部演示预留不落订单表，因此不进入订单的到期任务。订单预览继续只做预览。

### 复现验证

在构建库存模块后，可运行真实 MySQL 验证脚本：

```powershell
& ./deploy/verify/verify_inventory.ps1 -JavaHome 'C:\Users\RE\.jdks\ms-17.0.20'
```

验证使用随机端口、关闭 Nacos 注册，创建独立测试库存并在结束时删除自身记录。检查并发不超卖、相同订单重试、确认与释放竞争、本地事务回滚、错误响应及追踪标识。它会首次创建库存库并执行 Flyway，不是吞吐量压测；不运行库存演示脚本，也不修改活动数据。

## 订单调用库存

订单服务新增 `InventoryClient`，与已有 `EventClient` 一样通过 `@LoadBalanced RestClient` 按 Nacos 服务名发送 HTTP 请求。默认目标为 `ticket-inventory-service`，连接/读取超时分别为 2 秒/5 秒；可用 `INVENTORY_CLIENT_CONNECT_TIMEOUT`、`INVENTORY_CLIENT_READ_TIMEOUT` 覆盖。两个客户端分别使用命名的 Builder 和 `@Qualifier`，各自配置超时。订单模块不依赖库存 Maven 模块，不连接库存数据库。

| 客户端方法 | 库存端 HTTP 接口 |
| --- | --- |
| `reserve(request)` | `POST /internal/stock-reservations` |
| `confirm(reservationId)` | `POST /internal/stock-reservations/{reservationId}/confirm` |
| `release(reservationId)` | `POST /internal/stock-reservations/{reservationId}/release` |
| `getReservation(reservationId)` | `GET /internal/stock-reservations/{reservationId}` |

当前订单业务层接入 `reserveStock`。启动 Nacos、库存服务和订单服务后，直接向订单端口发送：

```http
POST http://localhost:8062/internal/orders/stock-reservations
Content-Type: application/json
```

```json
{
  "orderId": "DEMO_ORDER_001",
  "sessionId": 1,
  "ticketTierId": 3,
  "quantity": 2,
  "expiresAt": "2030-01-01T12:15:00.000"
}
```

场次和票档换成库存中实际的 ID，时间换成晚于当前时间的东八区时间（最多毫秒）。调用链为：

```text
InternalOrderStockController
    → OrderServiceImpl.reserveStock
    → InventoryClient.reserve
    → HTTP /internal/stock-reservations
    → InventoryServiceImpl → MySQL
    → 返回预留结果给订单服务
```

响应为 `Result<OrderStockReservationVO>`，返回实际预留 ID、订单关联编号、场次、票档、数量、到期时间和状态。重复请求保持完全相同的 JSON 参数，包括到期时间；当前状态可能是 RESERVED、SOLD 或 RELEASED，终态结果不会重新扣库存。

这是原内部库存协作接口，不创建订单、不检查活动规则或认证用户；正式下单使用下文的 `POST /api/orders`。网关不转发此内部路径，`/api/orders/preview` 不会预留库存。当前内部路径尚未接入服务身份校验。

符合契约的库存 400/404/409 分别保留为参数错误、资源不存在和业务冲突；依赖不可用返回 503，超时返回 504，响应字段/状态不一致或异常重定向返回 502。请求的 traceId 会传到库存服务。

客户端没有自动重试。超时、断连或错误响应可能发生在库存已提交之后；此时不要换编号重新预留或自动释放。使用相同编号、场次、票档、数量和到期时间重试；已知预留 ID 时可调用 `getReservation` 核对状态。

### 客户端与调用链验证

先构建订单与库存模块：

```powershell
mvn "-Dmaven.repo.local=$PWD/target/.m2" -pl 'ticket-order-service,ticket-inventory-service' -am package
```

不依赖 Nacos/MySQL 的真实 HTTP 桩验证：

```powershell
& ./deploy/verify/verify_inventory_client.ps1 -JavaHome 'C:\Users\RE\.jdks\ms-17.0.20'
```

使用本机 Nacos/MySQL 的真实跨进程验证：

```powershell
& ./deploy/verify/verify_order_inventory_chain.ps1 -JavaHome 'C:\Users\RE\.jdks\ms-17.0.20'
```

此调用链验证在独立端口和临时 Nacos 分组启动服务，只生成自身测试库存记录，结束后清理这些记录及验证进程。订单服务启动会执行订单库迁移；原内部预留验证不写订单业务记录，也不添加业务演示库存。

本步骤验证已通过：153 项客户端检查涵盖两实例负载均衡、四个调用方法、JSON 序列化、traceId、参数/状态响应核对、业务错误码、重定向、响应头/正文超时、不自动重试及活动预览回归；真实跨进程调用链完成 134 项检查，覆盖重复预留、库存不足、冲突和已售/已释放记录重试，并核对 MySQL 数量。临时记录和验证进程已清理。正文读取超时通过底层请求工厂保留超时异常原因，返回 504，格式错误仍为 502。
