# 分布式票务交易平台

基于 Spring Cloud 的票务后端学习项目。第一版完成活动发布、下单、库存预留、模拟支付、电子票及故障恢复；第二版已完成下表六块优化的选定方案。

## 第二版能力

| 范围 | 已实现方案 | 说明 |
| --- | --- | --- |
| 消息可靠传递 | RabbitMQ + Outbox、发布确认、消费幂等、延迟重试、死信重投 | [支付消息](docs/payment-mq.md) |
| 缓存与入口准入 | 活动缓存、跨实例重建锁、Redis Lua 网关令牌桶、受控拒绝 | [缓存与限流](docs/event-cache-rate-limit.md) |
| 热点库存保护 | Redis 按票档限制并发，在数据库事务前削峰；MySQL 保持库存权威 | [库存保护与监控](docs/hot-stock-monitoring.md) |
| 服务故障隔离 | Resilience4j 熔断、并发隔离、超时和重试边界 | [HTTP 调用保护](docs/http-resilience.md) |
| 可观测性 | HTTP、MQ、后台恢复链路追踪；Prometheus 指标、Grafana 11 个面板及 10 条告警规则 | [链路追踪](docs/http-tracing.md)、[监控验收](docs/hot-stock-monitoring-acceptance.md) |
| 部署与故障验证 | Docker Compose、启动入口、订单双实例、恢复任务接管、读取故障切换 | [Docker 部署](docs/docker-deployment.md)、[故障切换](docs/order-failover.md) |

此外，六个服务已接入 [Nacos 配置中心](docs/nacos-config.md)，支持日志级别和 Event 缓存 TTL 动态刷新。

## 启动与使用

准备 Docker Desktop、JDK 17、Maven，以及 Nacos 3.1.2 官方发行包；按 [Docker 启动指南](docs/docker-deployment.md) 完成本地环境和密码设置。

Windows 在项目根目录双击：

| 文件 | 用途 |
| --- | --- |
| `更新并启动.cmd` | 首次启动或修改 Java 代码后使用，打包并构建镜像 |
| `启动项目.cmd` | 日常启动，复用已生成的 JAR，启动两个 Order 实例 |
| `停止项目.cmd` | 停止项目容器，保留数据卷 |

也可在项目根目录 PowerShell 执行 `./deploy/docker/start.ps1`。脚本生成的密码文件、密钥和服务凭证保存在忽略目录 `.local/` 中。

| 入口 | 地址 |
| --- | --- |
| 业务 API 网关 | http://localhost:18060 |
| Grafana | http://localhost:3002 |
| Prometheus | http://localhost:19090 |

项目通过 API 演示。请求顺序、认证配置及导入文件见 [Apifox 使用指南](docs/apifox/README.md)，完整服务关系及交易顺序见 [架构说明](docs/v1-architecture.md)。

## 工程结构

Java 17、Spring Boot 4.0.8、Spring Cloud 2025.1.3、Spring Cloud Alibaba 2025.1.0.0、MyBatis-Plus 3.5.17，版本由父 `pom.xml` 管理。

| 模块 | 默认业务端口 | 职责 |
| --- | --- | --- |
| ticket-gateway | 8060 | WebFlux 路由、认证、限流和读取故障切换 |
| ticket-event-service | 8061 | 活动、场次、票档、发布及库存准备 |
| ticket-order-service | 8062 | 订单、价格快照、限购、履约恢复、消费记录和电子票 |
| ticket-inventory-service | 8063 | 实际库存、预留记录及热点准入 |
| ticket-payment-service | 8064 | 模拟付款、Outbox、通知及模拟冲正 |
| ticket-auth-service | 8065 | 用户、JWT 凭证及 Redis 会话 |
| ticket-common | 不独立启动 | Result、异常和基础支持 |
| ticket-security | 不独立启动 | 用户认证和服务凭证支持 |
| ticket-resilience | 不独立启动 | HTTP 熔断与并发隔离 |
| ticket-observability | 不独立启动 | 追踪上下文、异步 Span 和工作流指标 |

五个独立业务库共 14 张业务表，另有各库的 Flyway 历史表。业务服务采用 Spring MVC，只访问自己的数据库，通过 HTTP 和 RabbitMQ 协作。

## 验证

- [第一版功能验收](docs/v1-acceptance.md)：312 项功能与故障检查。
- [Docker 交易与双实例验收](docs/docker-acceptance.md)：完整交易、MQ、数据保留、租约接管及读取故障切换。
- [热点库存与监控验收](docs/hot-stock-monitoring-acceptance.md)：9 个 JUnit 测试、141 项库存回归、50 个请求抢 10 张票，以及真实告警触发和恢复。

复现脚本保留在 `deploy/verify/` 和 `deploy/docker/`。原始日志、机器结果和本地压测数据不随仓库发布。上述结果来自本机有限样本，不代表生产容量或多机高可用验收。

## 范围与仓库资料

支付与冲正为模拟功能；目前没有业务前端或入场核验。跨服务一致性使用本地事务、持久化进度、幂等、事实核对及重试补偿；付款事件使用 Outbox 和消费幂等。

第二版热点方案采用并发准入，库存数量仍由 MySQL 维护；告警可在本地查看。Redis 库存预扣账本、库存分段和外部告警通知渠道不在本次选定范围内。

仓库保留源码、自动化测试、数据库迁移、部署配置、启动入口、API 契约和对外文档。个人学习笔记、简历素材、IDE 配置、私有凭证及运行输出由 `.gitignore` 排除；`docs/` 使用公开文档清单，新增对外文档时需补充允许规则。
