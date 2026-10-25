# 交易可靠性与部署运维

## 前置预扣、规则与异步购票

### 数量与座位的一致性边界

Inventory负责Redis准入账本和自己的持久化意图。Lua原子检查可用量及整组选中座位，命中后
保存 `t_stock_admission`，再返回成功；Order正式订单、额度与快照仍使用订单自己的本地事务。
正式预留把意图改为 `APPLIED`，并在同一库存事务中扣MySQL数量、变更座位和保存预留。

Redis不是MySQL的同步镜像。重建在库存行锁内使用
`MySQL available_quantity - 尚未落实的HELD数量`，同时重建真正可选的座位和原请求去重记录。
每次重建生成代次并落库，旧代次尚未保存的请求不能继续提交。
Redis复制切换遗失已确认写入时，MySQL准入总额和座位条件更新仍保留最后护栏。

取消先持久化 `CANCELLED` 墓碑，再修复Redis；它与正式预留共用库存行锁。
到期只触发核对，不靠TTL删除记录来推断释放成功。响应丢失、连接超时或读取不到瞬时记录时，
保持原编号核对，不能直接INCR归还库存。正式预留已落实时走原release流程；Redis数量归还可能
等待下一轮重建，期间采取保守拒绝，不能绕过最终库存校验。

参数：Inventory与Order的 `TICKET_STOCK_ADMISSION_ENABLED` 必须一致。Compose/HA默认开启；
IDEA需显式配置。默认每5秒扫描，每轮最多20个到期票档，正常账本30秒进入核对；
大量票档或Redis中断时恢复时间受积压和重试影响，不承诺固定恢复时限。

### 规则和额度

Drools DRL随应用构建，在启动时编译，购买请求使用独立会话。
Event管理员配置的是经过校验的类型化参数，不上传可执行DRL。草稿发布或回滚影响新请求；
已受理请求及订单保存规则版本与参数快照，后台不会重算价格或替换成最新规则。

Order在本地事务中锁用户活动额度，维护不同场次名额、场次引用次数及累计票数。
同场次多张订单只占一个场次名额，最后一张有效订单关闭或冲正完成后才归还。
规则引擎的Java判断不能替代数据库并发控制。

### 异步受理及治理

`POST /api/orders/requests` 返回202前，受理与 `t_order_outbox` 已同事务提交。
建单队列为 `<prefix>.order.purchase-requested`，交换机为 `<prefix>.purchase.exchange`，
与支付成功消息完全独立。消费者核对原Outbox身份，从数据库读取权威购买快照，
订单、额度及受理完成标记在同一事务提交；重复消息不重复建单。

临时业务失败保存下一次恢复时间再ACK；数据库进度保存失败保留原消息并指数退避重投。
非法消息只有在死信转发Confirm成功且无Return后才ACK。死信队列为
`<prefix>.order.purchase-dead`，持续转发失败上限30秒退避，不静默丢弃原消息。
Relay与受理恢复使用专用有界调度池，Broker等待不占用原订单履约调度线程。

建单Outbox每轮失败20次后进入 `REVIEW_REQUIRED`。管理员先查看
`GET /api/orders/admin/purchase-outbox/review`，核对原受理、正式订单及预扣事实，
修复故障后调用 `POST /api/orders/admin/purchase-outbox/{eventId}/replay`，填写原因。
原事件ID不变，操作人和原因记录在 `t_order_outbox_replay`；不能删除事件或修改其请求归属。

工作流指标包含 `purchase_acceptance`、`purchase_outbox` 和 `purchase_outbox_review`；
MQ深度包含 `purchase` 和 `purchase_dead`。原积压和人工核对告警也覆盖新流程。

### 选座数据和查询

Event保存区域尺寸与不可变摘要；Inventory保存可选、HELD、RESERVED、SOLD状态；
Order保存座位快照，出票后每张电子票带对应座位。普通票继续使用空座位列表。
布局只允许草稿配置，首次座位初始化要求完整可用库存且无购买历史；同摘要重试不重置座位。

演示页只渲染可见行列。列表使用唯一ID游标，座位状态按视窗、固定through版本分页；
页内变化版本相同也不能提前跳过剩余座位。本批结束才推进since，变更视窗从零读取。
单次状态响应最多200条，视窗最多20行、50列，单票档布局最多20000席。

### 回归命令

原 `-Integration` 开启随机MySQL测试库；`-Redis` 增加共享准入和预扣恢复测试；
`-RabbitMQ` 增加真实发布确认/故障恢复检查并要求同时指定 `-Integration`。
测试只创建和删除随机测试库，不复用业务表；中间件地址与密码需按本机环境配置。

```powershell
./deploy/tests/run-regression.ps1 -Integration -Redis -RabbitMQ `
  -MySqlBase 'jdbc:mysql://127.0.0.1:13306/' -RedisPort 16379 -RabbitMqPort 5672 `
  -JdkHome '你的JDK17目录'
```

MySQL凭证从现有Windows环境读取。RabbitMQ测试默认连接本地guest账号，需使用允许guest本地连接的
独立测试Broker；不要将它指向生产环境。应用、测试和迁移变更后重新打包再更新容器。

## Outbox 人工核对与重放

自动发布默认每轮最多尝试 20 次或持续 24 小时。损坏载荷、Broker 认证失败立即进入
`REVIEW_REQUIRED`，临时连接、路由或 Confirm 故障在预算内指数退避。达到阈值只暂停自动发布，
原事件和已成功付款的事实始终保留。`TicketManualReviewRequired` 对待核对事件告警。

先修复故障并核对付款、订单、库存事实，再重放。设置 `JAVA_HOME`、`PAYMENT_DB_URL`、
`LOCAL_MYSQL_USERNAME`、`LOCAL_MYSQL_PASSWORD` 环境变量，使用可访问 Payment 库的运维账号。
密码从现有 Windows 环境或受控终端注入，不写进命令参数或仓库。

```powershell
# 默认只列出待核对事件，不改变数据库
./deploy/mq/review_outbox.ps1
# 显式重放原编号，必须填写操作人和原因
./deploy/mq/review_outbox.ps1 -EventId <原eventId> -Operator <操作人> -Reason '已恢复Broker路由并核对付款事实'
```

重放会锁定待核对记录，校验原支付成功事实与消息归属，在同一事务内写入
`t_outbox_redrive` 审计记录和恢复待发布状态。保留 `event_id`、原载荷、付款编号和总尝试次数，
只重置本轮自动预算。重复操作和活跃租约记录不能被重放。确认已发布不等于订单已出票，
最终仍需检查订单、消费记录及电子票。

相关参数：`ticket.mq.outbox-max-attempts`、`ticket.mq.outbox-max-age`。

## 消费转发故障

业务处理失败仍按原有三档延迟重试，之后保存到死信队列。若重试/死信转发本身失败，
专用消费线程按 1、2、4、8、16、30 秒上限并带随机抖动等待，再重新入队原投递，
不 ACK 未确认的转发。进程内所有消息共享失败进度，重新投递和切换消息不会重置退避。
目标转发得到 Confirm 且未收到 Return 后才重置进度。

`prefetch=1` 限制每个消费线程持有的未确认消息；多个实例各自退避。最大等待可配置，
启动时限制为不超过一分钟。进程退出、等待中断和连接断开均由 Broker 保留未确认消息。
主队列继续保留无限投递，以免单纯达到投递次数就丢弃付款线索。

监控 `ticket_mq_forwarding_failures_total`、`ticket_mq_forwarding_consecutive_failures`、
`ticket_mq_forwarding_backoff_milliseconds` 和 `ticket_mq_deliveries_total`；
`TicketPaymentForwardingStalled` 与 `TicketPaymentRedeliveryHigh` 提供告警。

## 自动回归

```powershell
./deploy/tests/run-regression.ps1
# 显式开启真实MySQL事务/并发测试，读取已有Windows数据库变量
./deploy/tests/run-regression.ps1 -Integration
```

集成测试连接默认 `jdbc:mysql://127.0.0.1:3306/`，可用 `TICKET_TEST_MYSQL_BASE` 改为独立测试实例。
账号需要创建/删除测试库和创建触发器权限。每个测试类生成 `ticket_test_<随机编号>` 库，
运行当前 Flyway 迁移，结束只删除自己创建的库，不向业务库插入或清理数据。
测试失败时命令返回非零，详细结果在各模块 `target/surefire-reports`，不会提交运行日志。

覆盖库存最后一张票竞争、丢失响应后的幂等恢复、库存写入失败回滚、确认与释放竞争、
订单快照写入失败、旧租约覆盖保护、消费事务回滚、业务提交后 ACK 失败再投递、
付款成功与 Outbox 原子提交，以及发布暂时失败后的原事件恢复。
ACK 窗口使用真实数据库提交加模拟 ACK 连接异常；整个进程被强制终止的端到端演练仍应在部署环境执行。

## 可信代理与客户端限流

默认不信任任何转发头。使用 `ticket.rate-limit.trusted-proxies` 或环境变量
`TICKET_RATE_LIMIT_TRUSTED_PROXIES` 明确列出代理的 IP/CIDR，例如 `10.0.0.10/32,fd00::10/128`。
保留 `server.forward-headers-strategy=none`，避免框架提前替换原始对端地址。
仅对可信直连代理提供的 `X-Forwarded-For` 从右向左解析，遇到第一个非可信地址就停止；
重复头、主机名、非法地址和超长代理链回退到直连地址。已认证请求仍优先按用户限流。

单入口 Nginx 示例覆盖客户端转发头，部署前确保网关只接受入口代理访问：

```powershell
docker compose -f compose.yml -f compose.proxy.yml up -d
# 通过 http://localhost:18000 访问；演示代理固定为172.31.244.250
```

示例网段如与本机网络冲突，应同时调整 Compose 网段、代理固定地址和可信代理配置。
多级代理需逐级规范化转发头并分别限制网络来源，禁止配置覆盖全部地址的可信范围。

## 数据库容量计划

Event 启动时检查 `planned-instances × database-concurrency ≤ cluster-load-budget`，以及
`database-concurrency + reserved-connections ≤ Hikari maximum-pool-size`。默认单实例16个回源、
连接池20、预留4；两个实例保持原总预算时，各自改为8并声明实例数2。
参数通过 `EVENT_DATABASE_CONCURRENCY`、`EVENT_PLANNED_INSTANCES`、`EVENT_CLUSTER_LOAD_BUDGET`、
`EVENT_DB_POOL_SIZE`、`EVENT_RESERVED_DB_CONNECTIONS` 设置，容量参数调整后重新部署。

```powershell
./deploy/capacity/check-capacity.ps1
./deploy/capacity/check-capacity.ps1 -EventInstances 2 -EventConcurrency 8 -MySqlMaxConnections 180
```

部署前把所有访问同一MySQL主节点的服务副本、连接池、Nacos外部数据源及运维连接一起计算。
第二个示例要求MySQL实际配置至少180个连接；也可通过降低每实例连接池上限维持原总连接预算。
该工具在超额时返回非零；它与启动检查验证的是声明的容量计划，不能发现未登记的额外实例，
也不构成分布式并发锁。扩容工具必须同步更新计划，不能只增加副本。
监控 `ticket_event_database_planned_loads`、`ticket_event_database_planned_connections` 和 Hikari
活跃/等待连接，结合 SQL 延迟与数据库 CPU/IO 压测调整预算；示例数值不是生产吞吐承诺。

## 独立 HA 部署

日常 `ticket-platform` 保留原来的轻量部署。`deploy/ha/` 以独立 `ticket-ha` 项目提供多节点拓扑，
使用自己的数据卷、网络和 `.local/ha/` 私有配置，业务数据不会自动从日常库迁移。

| 组件 | 拓扑与客户端接入 |
| --- | --- |
| MySQL | 三节点 Group Replication 单主模式；两个 HAProxy 只转发到 `ONLINE PRIMARY`，JDBC 配置两个代理地址 |
| Redis | 一个主节点、两个副本、三个 Sentinel，quorum=2；Spring 使用 Sentinel 发现主节点 |
| RabbitMQ | 三节点集群，业务队列为三成员 quorum 队列；客户端配置三个 Broker 地址 |
| Nacos | 三节点、统一身份密钥及外部 MySQL；客户端配置三个注册地址 |
| 业务与入口 | 可选六种服务各两个副本，两个 Nginx 入口；代理覆盖转发头，网关只信任这两个地址 |

JDBC使用普通多主机故障切换URL `jdbc:mysql://mysql-proxy1:3306,mysql-proxy2:3306/...`，
保留一个物理会话直至连接故障，不使用逐事务轮换的 `loadbalance` 模式，避免影响Flyway会话命名锁。
数据库切换时已有事务可能报错，仍由业务幂等与事实核对恢复，不承诺所有在途请求都无感成功。

MySQL 主库与代理不是五套独立数据库集群；五个逻辑业务库共享同一个三节点复制组。
Redis 使用异步复制，Sentinel 不能承诺故障时零数据丢失。实际库存与付款事实仍保存在 MySQL，
Redis 故障后的网络结果不确定仍由已有幂等、核对和恢复任务处理。

### 启动与停止

需要 Docker Linux 引擎、JDK17、已打包的业务 JAR、Nacos3.1.2 官方发行包。
完整拓扑运行大量进程，建议为 Docker 分配至少16GiB内存；资源不足时先只启动基础设施，
不要与日常完整部署同时运行。基础镜像和 HAProxy 构建依赖需要能访问镜像仓库与 Alpine 软件源。

```powershell
# 在项目根目录；默认读取.local/nacos-3.1.2/nacos，也可指定-NacosHome
./deploy/ha/start.ps1

# 完成Maven package后，构建并启动业务副本；不会自动重新打包旧JAR
./deploy/ha/start.ps1 -WithApplications -JdkHome '你的JDK17目录'

# 内存较少时验证基础设施与业务接入，每种业务只运行一个副本；此模式没有业务副本冗余
./deploy/ha/start.ps1 -WithApplications -ApplicationReplicas 1 -JdkHome '你的JDK17目录'

# 只停止ticket-ha容器，保留数据卷、凭证和备份
./deploy/ha/stop.ps1
```

完整业务入口为 `http://localhost:18070` 与 `http://localhost:18071`。
管理员为 `ha_admin`，密码文件为 `.local/ha/auth-admin-password`；Nacos管理账号为 `nacos`，
密码文件为 `.local/ha/nacos-admin-password`。随机密码由脚本首次生成，从只读文件挂载读取；
已有数据卷的凭证不得随意重新生成。JWT密钥和内部调用凭证复用项目已有私有目录。
签名私钥与管理员初始化密码只挂载到Auth；服务调用凭证按调用方向分配给对应服务。

示例使用 `172.30.246.0/24` 固定容器网段，避免节点停止造成DNS阻塞或旧地址残留。
若网段冲突，需要同步修改 Compose、Redis/Sentinel、Nacos成员配置、网关可信代理和探针地址。
仅数据库代理及业务入口绑定本机回环端口；集群管理端口不直接向外暴露。

### 故障演练

```powershell
./deploy/ha/verify.ps1 -JdkHome '你的JDK17目录' -RestoreCheck

# 业务副本已启动时，验证登录、发布、MQ付款、出票和数据库事实；会保留本次随机业务记录
./deploy/ha/verify-business.ps1
```

脚本只操作当前 `ticket-ha` 项目，依次停止一个节点并验证：

1. MySQL自动选主、原提交保留、两个代理可以继续写入，旧主重新加入三节点组。
2. Redis已由两个副本确认的探针数据在切换后可读，新主可以继续写入。
3. RabbitMQ探针队列Leader停止后，原持久消息保留，仍能发布Confirm并消费新消息。
4. Nacos一个节点停止后，其余节点仍能鉴权并读取已保存配置。
5. 备份SHA256通过，恢复到独立MySQL数据卷后核对原探针记录。

停止的节点在 `finally` 中恢复；终端被强制结束时仍应检查容器状态。
SQL、Redis和HTTP探针设有超时，失败返回非零。原始日志和结果只保存在 `.local/ha/`。
三节点多数派只允许同时故障一个节点；多数派丢失时停止写入是预期保护。
MySQL启动脚本仅允许在三个全新空库上引导新组，已有业务库不会自动清空GTID或强制覆盖成员。
整个MySQL组断电后若不能自行恢复，应先停止业务写入、核对所有成员的GTID和已接收事务，
选择最完整的成员按MySQL Group Replication灾难恢复流程人工恢复；禁止直接删卷或重建空组。

### 备份与隔离恢复

```powershell
$backup = ./deploy/ha/backup.ps1
./deploy/ha/restore-check.ps1 -BackupDirectory $backup
```

默认备份五个业务库与Nacos库，逐库 `--single-transaction` 导出并生成SHA256清单。
它不是跨库同一时点快照；全系统一致性恢复需安排停止写入的备份窗口，或结合binlog与交易事实核对。
备份包含用户与支付数据，文件留在忽略目录；长期保存应另行加密并复制到独立故障域。

恢复工具只接受本项目私有目录中的校验通过文件，目标固定为独立 `mysql-restore` 数据卷，
遇到已有同名库直接拒绝覆盖。重复运行 `-RestoreCheck` 时可先改为不带此参数完成节点切换演练，
再单独检查已有恢复库；需要再次完整恢复时准备新的隔离目标，不能对原业务库删除后重试。
`verify.ps1`额外核对恢复后的原探针记录，普通恢复工具输出恢复表数量以便进一步业务核对。

### 生产环境边界

同一Docker Desktop中的多容器只能验证单容器故障处理，宿主机和存储仍是共同故障域。
跨服务器部署必须把副本分布到独立节点，提供冗余入口地址或外部负载均衡、TLS和网络隔离，
替换示例root数据库连接为各服务最小权限账号，接入独立告警通知与异地备份，
并在实际环境验证分区、整机故障、全停恢复、容量与RPO/RTO。
当前可选HA栈不包含额外监控集群；日常部署的Prometheus/Grafana仍保留，
跨环境接入时应另外配置采集目标。项目中的支付、冲正仍为模拟业务。
