# 管理员草稿、库存准备与发布

## 完成的功能

管理员一次提交活动、场次、票档、开售时间、累计限购量和计划库存，Event在自己的本地事务中保存完整草稿。草稿不能被公共活动查询或购票规则接口读取。

Event后台使用稳定票档ID、场次ID和计划数量调用Inventory初始化。初始化成功并核对实际事实后才保存READY；全部售卖票档READY，管理员才可发布。库存数量仍只属于Inventory；Event的planned_quantity是初始化请求快照，不是实时可售量。

第一版管理入口只支持创建完整草稿、查看进度、用原参数重新准备、发布。没有修改库存总量、编辑已提交初始化参数、下架、取消场次或删除已初始化草稿的入口；后续修改功能要单独设计与已有订单的关系。

## 接口

所有管理接口携带管理员的 `Authorization: Bearer <accessToken>`，Gateway与Event各自验签、校验会话和ADMIN角色。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | /api/admin/events | 创建完整草稿，相同幂等键及请求返回原活动 |
| GET | /api/admin/events/{eventId} | 查看草稿、场次、票档与准备进度 |
| POST | /api/admin/events/{eventId}/prepare | 将需核对票档按原参数重新排入准备，并尝试推进一个票档；其余由后台处理 |
| POST | /api/admin/events/{eventId}/publish | 全部售卖票档READY才发布；重复发布返回原活动 |

创建请求示例（时间按东八区，不含时区后缀，精度最多毫秒）：

```json
{
  "idempotencyKey": "concert_001",
  "name": "演唱会",
  "category": "CONCERT",
  "sessions": [{
    "name": "首场",
    "venueName": "体育馆",
    "venueAddress": "场馆地址",
    "startTime": "2027-06-01T19:00:00",
    "endTime": "2027-06-01T21:00:00",
    "saleStartTime": "2027-05-01T10:00:00",
    "saleEndTime": "2027-06-01T18:00:00",
    "purchaseLimit": 2,
    "ticketTiers": [{"name": "普通票", "price": 199.00, "totalQuantity": 100}]
  }]
}
```

coverUrl和description可选。至少一个场次，每场至少一个票档；每次最多20场，每场最多20个票档。停售不得晚于演出开始，演出结束必须晚于开始。创建幂等按解析后请求的JSON摘要核对，重试保留全部字段、列表顺序和金额表示；同键不同参数返回409。

草稿创建成功只代表Event已落库，不代表库存准备成功；查看票档的preparationStatus。发布失败409时，活动与场次保持草稿。发布只核对本地持久化事实，不在发布事务内执行HTTP。

## 准备状态与恢复

```text
完整草稿 + 每个票档固定初始化参数
    ↓ 本地事务提交
PENDING → 领取令牌及租约 → HTTP初始化Inventory
    ├─ 确认匹配 → READY
    ├─ 超时/503/本地保存失败 → PENDING，持久化退避后恢复
    └─ 参数冲突/事实不符/服务凭证被拒绝 → REVIEW_REQUIRED，阻止发布
全部售卖票档READY → 活动和场次在Event本地事务中一起发布
```

领取令牌与60秒租约存在数据库；多个Event实例竞争领取，过期后可恢复，旧线程不能覆盖新领取者。HTTP在事务外执行，重试保持原ID和数量；Inventory以票档主键协调初始化并核对场次及原始总量。

初始化接口为 `POST /internal/stocks/initializations`，查询事实为 `GET /internal/stocks/initializations/{ticketTierId}`。仅Event服务凭证可以访问，用户Token不能代替此凭证。重复初始化保留原可用、预留、售出数量，不重置库存。

永久矛盾不会自动覆盖数据。管理员核对原因后可以用prepare重新尝试原参数；该接口不能把一个冲突的库存数量强行改成草稿数量。

## 管理员账号与启动

1. 重新加载Maven，先启动MySQL、Redis、Nacos。
2. 首次管理员初始化，在Auth运行配置填写 `AUTH_BOOTSTRAP_ADMIN_USERNAME` 和 `AUTH_BOOTSTRAP_ADMIN_PASSWORD`。账号为3至32位字母数字下划线，密码8至64字符且UTF-8不超过72字节；不提供默认密码。
3. 启动新版Auth：Flyway V2增加role字段，初始化缺失管理员，BCrypt保存密码。若同名普通用户或密码不匹配则拒绝启动，不覆盖已有账号。初始化完成后可移除这两个配置，后续用原密码登录。
4. 重启新版Inventory、Event、Gateway；Event需要Redis和Auth公钥地址。工作目录设为项目根目录，继续使用已有Windows数据库变量。
5. 管理员通过现有 `/api/auth/login` 登录，使用新的Access Token访问管理接口。已有无role的Token按USER处理，需要重新登录取得ADMIN声明。

公共注册固定USER，请求正文中的role不能提权。角色由Auth数据库签入Access/Refresh，刷新时重新读取角色。直接改数据库角色不会立即撤销已经签发的Access；需要退出对应会话并重新登录，当前没有管理员角色管理/全部会话撤销接口。

本机已生成 `.local/service-credentials/event-inventory.token`，Event和Inventory读取同一文件，内容不输出到日志且不进入Git。新环境仅在文件缺失时执行：

```powershell
java deploy/auth/GenerateServiceCredential.java .local/service-credentials/event-inventory.token
```

可以用 `EVENT_INVENTORY_CREDENTIAL_PATH` 指定绝对文件路径，或用 `EVENT_INVENTORY_SERVICE_TOKEN` 指定64位小写十六进制随机凭证。文件缺失或格式错误会启动失败。跨主机使用TLS并独立分发凭证；Order库存预留/确认/释放入口及Event购票规则入口现已接入独立服务凭证，详见[内部服务身份](service-identity.md)。

Event Flyway V2在已有三张表增加幂等与准备字段，不新增Event业务表；Inventory复用原库存表，没有新迁移。历史票档标记LEGACY，不推断库存已准备好。既有已发布活动保持查询契约，历史草稿不能未经准备通过新发布入口。

## 按顺序阅读代码

1. Auth：`V2__add_user_role.sql`、`AdminBootstrap`、`AuthServiceImpl`、`TokenService`，创建管理员并签发可信角色。
2. Gateway：`GatewaySecurityConfig` 与路由，ADMIN访问管理接口，原Bearer传给Event。
3. Event：`AdminEventController` → `EventAdminService`接口 → `EventAdminServiceImpl` → `EventDraftTransaction.create`，保存活动、场次、票档和准备快照。
4. `PreparationMapper`、`EventPreparationScheduler`、`EventPreparationWorkflow`，持久化领取、HTTP调用和恢复。
5. `InventoryInitializationClient`，按服务名调用并核对票档、场次、总量及库存平衡。
6. Inventory：`InitializationSecurityConfig` → `InternalStockInitializationController` → `StockInitializationService` → `TicketStockMapper.initialize`，幂等保存初始化事实。
7. `EventDraftTransaction.publish`，全部准备好后原子发布活动与场次。

## 验证范围

`deploy/verify/verify_event_administration.ps1` 使用真实Auth、Gateway、Event、Inventory、Order、Payment和MySQL/Redis。库存初始化代理只注入故障；活动规则使用真实Event，购票过程中不使用活动规则桩。

验证管理员/普通用户权限、公共注册不能提权、角色刷新、草稿隐藏、创建幂等、事务回滚、多个票档发布条件、重复初始化不重置库存、响应丢失、503、本地READY保存失败、Event重启与双实例恢复，以及API创建活动经网关下单、模拟付款和出票。所有数据在随机schema、Redis前缀内隔离，结束只清理自身进程和数据。

测试关闭Nacos，业务调用使用固定SimpleDiscoveryClient实例、Gateway固定HTTP路由。本次验证业务和恢复正确性，不声称重复验证Nacos注册或完成性能压测。

增加内部服务身份验证后，已通过104项管理/购票及鉴权检查；此前Auth的59项回归检查已通过。
