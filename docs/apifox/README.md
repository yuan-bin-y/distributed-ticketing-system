# Apifox 导入与购票调试

这里提供第一版 API 的静态 OpenAPI 3.0.3 文档，按当前 Controller、DTO、VO 和安全配置整理。包含请求约束、响应模型、状态码和认证方式，无需新增 Swagger 运行依赖。

## 导入文件

| 文件 | 接口数 | 地址与用途 |
| --- | --- | --- |
| [ticket-public.openapi.json](ticket-public.openapi.json) | 22 | Gateway 8060，注册登录、活动管理、下单、模拟付款、票券与连通检查 |
| [ticket-internal.openapi.json](ticket-internal.openapi.json) | 13 | 各服务直连，库存协作、购票规则、支付协作、付款通知和Auth公钥 |

先导入公开接口即可演示第一版。Apifox 支持 OpenAPI 3.0 JSON 文件导入；新项目可选择“导入项目”，已有项目在“项目设置 → 导入数据”选择 OpenAPI/Swagger 并导入本地文件。[官方导入说明](https://docs.apifox.com/import-openapi-swagger)

导入预览应看到 22 个公开接口，按 tags 分为认证、活动查询、管理员、订单、模拟支付和连通检查。请求示例中的 ID 是占位示例，发送前换成真实返回值；响应模型与示例不代表本机已存在对应数据。

内部文档建议导入独立项目，或独立模块。各接口的 operation.servers 已标明 8061/8062/8063/8064/8065；导入后检查实际请求地址，避免模块统一前置URL覆盖服务端口。内部路径不能通过8060调用。

## 本地环境

按 [Docker 启动指南](../docker-deployment.md) 启动中间件和六个服务。Payment 的 `PAYMENT_SIMULATION_ENABLED=true` 只用于本地模拟。Auth 初始化管理员账号后，使用自己的账号密码登录。

在 Apifox 创建“本地网关”环境，Docker 部署的前置 URL 为 `http://localhost:18060`；IDEA 启动时为 `http://localhost:8060`。静态文档中的 806x 是业务默认端口，Docker 内部接口调试需改为对应宿主机端口：Event 18061、Inventory 18063、Payment 18064、Auth 18065；Order 内部接口仅在 Compose 网络中可达，不映射到宿主机。日常交易调试使用网关公开接口。配置以下变量，Token 和实际密码填在本地值中：

| 变量 | 初始内容 | 来源 |
| --- | --- | --- |
| access_token / refresh_token | 空 | 普通用户登录或刷新结果 |
| admin_token / admin_refresh_token | 空 | 管理员登录或刷新结果 |
| event_id / session_id / ticket_tier_id | 空 | 管理端草稿响应 |
| draft_json | 空 | 草稿前置脚本生成并保留完整请求 |
| order_json | 空 | 下单前置脚本生成并保留完整请求 |
| order_no / reservation_id / order_status | 空 | 下单、查询结果 |
| payment_no / payment_status | 空 | 从订单发起支付结果 |
| quantity | 2 | 本次购买数量 |
| inventory_ready / event_status | 空 | 管理端响应脚本提取 |

认证配置选择 Bearer Token，用户接口填写 `{{access_token}}`，管理员接口填写 `{{admin_token}}`，此处不要再加 `Bearer ` 前缀。如果直接配置 Authorization 请求头，才写 `Bearer {{access_token}}`；两种方式选一种。

注册、登录、刷新、GET活动查询与Auth/Event ping为匿名接口，可设“无需认证”。Order、Payment ping经Gateway需要Token；这与直连业务服务匿名ping不同。

Token 分为管理员与普通用户两组，避免后一次登录覆盖前一组。给同一个 `/api/auth/login` 建立“普通用户登录”和“管理员登录”两个调试用例，分别使用对应账号与后置脚本。

## 安装配套脚本

OpenAPI 导入只建立接口契约，本目录的脚本需手工复制到相应接口或用例。后置脚本用于提取响应变量；在“后置操作 → 添加后置操作 → 自定义脚本”中粘贴。前置脚本放入对应“前置操作”的自定义脚本。[官方脚本说明](https://docs.apifox.com/5581000m0)

| 接口或用例 | 前置脚本 | 后置脚本 |
| --- | --- | --- |
| 普通用户登录、普通用户刷新 | 无 | [user-token.post.js](scripts/user-token.post.js) |
| 管理员登录、管理员刷新 | 无 | [admin-token.post.js](scripts/admin-token.post.js) |
| 创建草稿 | [event-draft.pre.js](scripts/event-draft.pre.js) | [event-draft.post.js](scripts/event-draft.post.js) |
| 查询草稿、重新准备、发布 | 无 | [event-draft.post.js](scripts/event-draft.post.js) |
| 下单 | [order-create.pre.js](scripts/order-create.pre.js) | [order.post.js](scripts/order.post.js) |
| 查询订单 | 无 | [order.post.js](scripts/order.post.js) |
| 从订单发起支付 | 无 | [payment-create.post.js](scripts/payment-create.post.js) |

创建草稿的 JSON 请求正文整个替换为 `{{draft_json}}`，下单正文整个替换为 `{{order_json}}`，保持 JSON 类型，不在变量外再加双引号。前置脚本会生成变量；不要发送OpenAPI静态示例中的未来开售时间来验证“当前可购买”。

登录成功自动提取Token，但不会自动切换接口认证配置。上述脚本使用 Apifox 兼容的 `pm.response.json()`、`pm.response.code`、`pm.environment.set()` 和 `pm.variables.set()`。[官方脚本API](https://docs.apifox.com/pm-%E8%84%9A%E6%9C%AC-api-5580997m0)

## 按顺序演示

1. **注册**：`POST /api/auth/register`，填写新的普通用户账号、密码、昵称。公共注册固定USER；管理员必须由Auth首次初始化创建。
2. **普通用户登录**：`POST /api/auth/login`，运行用户Token后置脚本。
3. **管理员登录**：同一路径的管理员用例，运行管理员Token后置脚本。
4. **创建草稿**：`POST /api/admin/events`，使用管理员Token、动态草稿脚本。脚本设置一小时前开售、明天演出及10张普通票的场次。
5. **查看准备**：`GET /api/admin/events/{eventId}`，路径参数改为 `{{event_id}}`，隔几秒查询；确认 `inventory_ready=true`、各票档READY。若REVIEW_REQUIRED查看lastError。
6. **发布**：`POST /api/admin/events/{eventId}/publish`，管理员Token，无正文。未准备完409；准备完成后状态PUBLISHED。
7. **浏览**：`GET /api/events/{eventId}/sessions`，无需认证。公共场次和票档的ID字段是 `id`，不是管理端的 `sessionId/ticketTierId`。
8. **下单**：`POST /api/orders`，用户Token、下单前后置脚本。数量2，金额398.00。首次后再次发送同一个请求，应返回原订单号。不要清空order_json做重试。
9. **查询订单**：`GET /api/orders/{orderNo}`，路径填 `{{order_no}}`。202并非失败，查询到PENDING_PAYMENT再发起支付；200也需检查实际status。
10. **发起支付**：`POST /api/orders/{orderNo}/payments`，用户Token、无正文，保存payment_no。
11. **模拟成功**：`POST /api/payments/{paymentNo}/simulate-success`，路径填 `{{payment_no}}`，用户Token、无正文。该响应字段是status，表示付款事实。
12. **等待出票**：继续查询订单，经过PAYMENT_CONFIRMING、PAID，最终COMPLETED。默认后台周期约5秒，不把付款接口成功当作立即完成出票。
13. **查票**：`GET /api/orders/{orderNo}/tickets`，应得到两张不同票号，orderStatus为COMPLETED。
14. **退出**：`POST /api/auth/logout`，用户Token、无正文；再用原Token查订单应401。

刷新请求正文用 `{"refreshToken":"{{refresh_token}}"}`，管理员刷新改用 `admin_refresh_token` 并绑定管理员脚本。每次成功保存新Refresh，不能重复使用旧Refresh。

默认Access有效15分钟。超出期限重新登录或刷新后再发请求，不能把过期Token导致的401判为业务服务故障。

## 新演示与异常处理

新活动演示先清空 `draft_json`、活动标识、`inventory_ready`，让脚本生成新的草稿。新购买清空 `order_json`、订单及支付编号和相关状态；超时、202和不确定结果的重试都不能清空原购买请求。同场次用户已购两张后受累计限购，新的独立演示使用新活动或新用户。

| 结果 | 操作 |
| --- | --- |
| 401 | 核对Token、会话、账号与服务凭证；退出后的Token不可再用 |
| 403 | 检查管理员权限、服务调用方向与路径/HTTP方法 |
| 404模拟入口 | 确认模拟开关已开启、编号正确、支付单归属正确 |
| 409发布 | 检查库存准备状态 |
| 409下单 | 检查累计限购、幂等参数与业务状态 |
| 202或请求超时 | 保留原请求和键，查询订单；结果不确定不盲目重复购买 |
| REVIEW_REQUIRED | 查看服务日志及持久化错误，人工核对矛盾事实 |

## 内部接口调试

内部API文档的apiKey认证对应以下请求头。本地值来自对应文件，不在文档中预填实际凭证。只在隔离测试数据上手工调用写接口，以免绕过订单恢复流程。

| 调用方向 | 请求头 | 本地凭证文件 |
| --- | --- | --- |
| Order → Event | X-Order-Event-Credential | .local/service-credentials/order-event.token |
| Order → Inventory | X-Order-Inventory-Credential | .local/service-credentials/order-inventory.token |
| Event → Inventory | X-Event-Inventory-Credential | .local/service-credentials/event-inventory.token |
| Order → Payment | X-Order-Payment-Credential | .local/service-credentials/order-payment.token |
| Payment → Order | X-Payment-Order-Credential | .local/service-credentials/payment-order.token |

JWKS公钥直连8065，无认证且不包装Result。Inventory预留的字段为orderId，Payment/Order的业务编号字段为orderNo。库存SOLD和RELEASED不可互转；不要对已成交订单强制释放。

历史教学入口 `POST /internal/orders/stock-reservations` 仍有Controller，但当前安全链默认拒绝，不作为第一版可调用契约。正常购买使用公开下单接口。

## 文档校验与维护

第二版新增活动查询和订单接口的 `429 RATE_LIMITED` 响应说明与 `Retry-After`，并补充缓存/限流不可用时的 `503 SERVICE_BUSY`。导入更新后的公开 OpenAPI 即可同步这 8 个操作的错误契约；内部接口无需为本轮缓存和限流重新导入。具体范围和配置见[活动缓存与限流](../event-cache-rate-limit.md)。

两个JSON已使用OpenAPI官方JSON Schema校验（为本机Test-Json将校验器的Draft4语法等价适配至Draft7，导入文件仍为OpenAPI 3.0.3）。35个接口与当前Controller匹配，33个DTO/VO模型字段、10个请求示例、引用及路径参数已检查；7个脚本通过本地模拟pm对象验证，涵盖Token分离、失败不覆盖、草稿准备与下单幂等请求保留。

导入后仍须核对Apifox实际URL、认证继承及脚本配置；本次没有操作Apifox桌面客户端完成导入或执行新一轮真实交易。

业务接口变更时同步更新对应OpenAPI路径与schema。文件是静态交付文档，不会随Controller修改自动刷新，也没有提供 `/v3/api-docs` 运行端点。
