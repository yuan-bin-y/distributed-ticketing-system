# 交易可靠性与部署运维

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
