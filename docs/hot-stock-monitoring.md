# 第二版：热点库存保护与指标告警

## ③ 热点库存采用的方案

Redis按票档提供跨实例并发准入。每个票档默认允许2个预留事务同时进入数据库；满额立即返回503和`STOCK_BUSY`。订单保留原编号及参数，使用已有持久化退避重试，不创建新订单。不同票档使用不同key，互不占用额度。

阅读顺序：

1. `ticket-inventory-service/.../service/HotStockGate.java`：Lua使用Redis时间清理过期令牌，再原子检查数量、写入随机令牌。释放时只删除自己的令牌。
2. `ticket-inventory-service/.../service/impl/InventoryServiceImpl.java`：在事务开始前领取；用TransactionTemplate执行原有两表预留；提交或回滚后释放。
3. `ticket-inventory-service/.../web/handler/GlobalExceptionHandler.java`：正常削峰返回`STOCK_BUSY`；Redis故障仍返回`SERVICE_BUSY`。
4. `ticket-order-service/.../client/InventoryClient.java`：识别`STOCK_BUSY`，交给原恢复流程，不计入整个Inventory服务的熔断失败率。真实连接故障仍参与熔断。

库存最终事实仍由MySQL条件更新、唯一预留及事务保证。这里实现的是热点写入削峰，没有新增Redis库存账本或库存分段。准入减少同时争抢同一行的请求；不承诺一定提升总吞吐。

默认租约15秒，预留事务超时10秒。进程崩溃后令牌自动回收；释放Redis失败不会覆盖已经提交的业务结果。长时间停顿可能超过租约，准入并非严格的业务互斥锁；数据库继续防止超卖。

Compose默认开启；IDEA按需设置`INVENTORY_HOT_STOCK_ENABLED=true`。配置项：

| 配置 | 默认值 |
| --- | --- |
| `ticket.hot-stock.enabled` | false（Compose设为true） |
| `ticket.hot-stock.permits` | 2 |
| `ticket.hot-stock.lease-ms` | 15000 |
| `ticket.hot-stock.prefix` | `ticket:hot-stock:`（Compose使用隔离前缀） |

所有库存副本必须共享Redis、前缀和额度配置。确认、释放和事实查询不参与预留准入，避免满额时阻止恢复。

## ⑤ 指标、面板与告警

六个服务引入Micrometer Prometheus Registry。Compose在各容器内的9000端口开放只读`/actuator/health`与`/actuator/prometheus`，不映射到宿主机；业务端口禁止匿名访问指标。IDEA默认关闭管理端口；单服务本机调试可用`TICKET_MANAGEMENT_PORT`指定独立端口，默认仅绑定127.0.0.1。

Prometheus使用Docker DNS分别抓取各副本；两个Order共7个目标。数据保留7天。Grafana自动加载Ticket Prometheus数据源与Ticket Platform Overview面板，已有Tempo数据源继续保留。

- [指标面板](http://localhost:3002/d/ticket-platform-overview)
- [Prometheus目标](http://localhost:19090/targets)
- [当前告警](http://localhost:19090/alerts)

11个面板覆盖服务健康、请求率、P95、5xx、持久化任务数量、最旧积压、库存准入结果、数据库连接等待、采样健康、当前告警、MQ队列深度。

`WorkflowMetricsAutoConfiguration`定时查询各服务自己的数据库，抓取时只读内存；多副本的同库数量使用max聚合，避免重复相加。数据库采样失败保留上次值，并标记collector失败和样本时间；不能把采集失败当成没有积压。

`PaymentQueueMetrics`通过被动声明读取已存在的主队列、三档重试和死信队列，不消费消息。队列指标是待投递数量，不含已交给消费者但尚未ACK的消息。

10条告警覆盖：服务离线、服务发现不足、HTTP错误率、工作流长时间积压、需要人工核对、业务采样失败/过期、Redis准入故障、死信、MQ主队列积压、MQ采样失败。阈值是本机学习项目起点。告警在Prometheus页面及Grafana面板展示；未配置邮件、短信或其他外部通知渠道。

## 验证

PowerShell7执行：

```powershell
./deploy/docker/verify-hot-stock.ps1
./deploy/docker/verify-monitoring.ps1 -FaultTest
./deploy/docker/verify.ps1
```

热点脚本创建唯一库存夹具，50个并发请求争抢10张票，503只以原编号重试，最后清理自己的夹具。监控故障脚本短暂冻结Inventory，等待真实告警触发，并在finally恢复容器，验证告警消退。运行故障脚本期间不要同时操作这套演示环境。

告警规则单测：

```powershell
docker run --rm --entrypoint promtool --mount "type=bind,source=$((Get-Location).Path)/deploy/monitoring,target=/etc/prometheus,readonly" prom/prometheus:v3.14.0 test rules /etc/prometheus/alerts-test.yml
```

库存JUnit测试需显式传入`-Dticket.test.redis=true -Dticket.test.redis.port=16379`，使用真实Redis并清理随机前缀。未启用时测试跳过，不能把跳过算作已验证。

设计依据：[Spring Boot独立管理端口](https://docs.spring.io/spring-boot/4.0/how-to/actuator.html)、[Prometheus DNS发现与规则配置](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)。
