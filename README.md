# 分布式票务交易平台

基于 **Spring Boot / Spring Cloud** 的微服务票务系统，覆盖活动发布、选座购票、模拟支付和电子票生成，提供轻量购票展示页与完整 API。

项目包含 **6 个独立服务、4 个公共模块、5 个业务数据库**，重点实现库存防超卖、可靠消息、最终一致性、规则管理和故障恢复。

## 核心功能

- **活动发布**：创建活动、场次和票档，库存与座位准备完成后发布。
- **购票与限购**：同步下单、异步受理、重复请求幂等；支持每单、单场累计及跨场次限购。
- **库存与选座**：Redis Lua 预扣数量及座位，持久化账本支持核对重建；MySQL 保留最终数量与座位保护。
- **规则管理**：Drools 执行购票规则，支持参数版本发布、历史查询和回滚，订单保存规则快照。
- **支付与出票**：模拟付款、付款事实核对、库存确认、电子票生成及迟到付款模拟冲正。
- **异常恢复**：超时关单、库存释放、后台重试、多实例任务接管和人工核对。

## 技术栈与关键方案

| 方向 | 技术与实现 |
| --- | --- |
| 服务架构 | Java 17、Spring Boot、Spring Cloud、Nacos 注册发现与配置 |
| 数据持久化 | MySQL、MyBatis-Plus、Flyway；各服务只访问自己的数据库 |
| 认证与权限 | Spring Security、RS256 JWT、BCrypt、Redis 会话、内部服务凭证 |
| 高并发保护 | Gateway / WebFlux 限流、Redis Lua 预扣、热点缓存与重建锁、有界回源 |
| 可靠消息与一致性 | RabbitMQ + Outbox、发布确认、消费幂等、重试与死信；本地事务、状态机、事实核对及补偿 |
| 规则与异步购票 | Drools / DRL、规则版本快照、建单 Outbox、异步受理与结果查询 |
| 故障隔离 | Resilience4j 熔断、并发隔离、HTTP 超时及读取故障切换 |
| 可观测性 | Micrometer、OpenTelemetry、Tempo、Prometheus、Grafana |
| 展示与部署 | 原生 HTML / CSS / JavaScript、Docker Compose、Windows 启动脚本、OpenAPI / Apifox |

依赖版本见 [pom.xml](pom.xml)，容器配置见 [compose.yml](compose.yml)。

## 服务划分

| 模块 | 职责 |
| --- | --- |
| `ticket-gateway` | 统一入口、认证、路由与限流 |
| `ticket-auth-service` | 用户、登录凭证与会话 |
| `ticket-event-service` | 活动、场次、票档、规则与发布 |
| `ticket-inventory-service` | 库存预扣、正式预留、确认售出、释放与座位状态 |
| `ticket-order-service` | 订单、限购、异步受理、履约与电子票 |
| `ticket-payment-service` | 模拟支付、付款事件与模拟冲正 |

公共模块为 `ticket-common`、`ticket-security`、`ticket-resilience`、`ticket-observability`，作为依赖打入服务 JAR，不单独启动。

服务通过 **HTTP + RabbitMQ** 协作：Nacos 提供实例发现，LoadBalancer 选择调用目标，订单服务编排交易流程。

## 交易流程

```text
活动草稿 → 准备库存与座位 → 发布活动
                               ↓
用户登录 → 查询活动与规则 → Redis 预扣 → 同步建单 / 异步受理建单
                               ↓
                    模拟付款 → Outbox → RabbitMQ
                               ↓
                    核对付款 → 确认库存 → 生成电子票
```

异步入口返回 `202` 表示已受理，最终出票以订单达到 `COMPLETED` 为准。跨服务采用可恢复的最终一致性，网络超时通过原编号幂等、事实核对和后台补偿处理。

活动列表使用分页，场次和票档使用游标查询；选座页按视窗加载、版本增量更新和虚拟渲染，避免一次传输和渲染全部座位。

## 快速开始

准备 **Docker Desktop（Linux 引擎）、JDK 17、Maven、Nacos 3.1.2 发行包**，按 [部署指南](docs/docker-deployment.md) 设置数据库环境变量和 Nacos 路径。

| 根目录双击入口 | 用途 |
| --- | --- |
| [更新并启动.cmd](更新并启动.cmd) | 首次运行或修改代码后，打包、构建镜像并启动 |
| [启动项目.cmd](启动项目.cmd) | 日常启动，复用已有 JAR，默认运行两个 Order 实例 |
| [停止项目.cmd](停止项目.cmd) | 停止项目容器，保留数据卷 |

已有旧版本数据时，先停止业务服务，再按部署指南升级。私钥、密码、内部凭证和运行输出保存在 Git 忽略目录 `.local/`。

- 购票展示页：http://localhost:18060/ticket-demo/index.html
- API 网关：http://localhost:18060
- Grafana：http://localhost:3002
- Prometheus：http://localhost:19090

管理员通过 API 创建并发布活动后，用户即可在展示页完成选座、购票、模拟付款和查看电子票。

## 文档

- [部署与启动](docs/docker-deployment.md)
- [API / Apifox 使用指南](docs/apifox/README.md)：35 个公开接口、17 个内部接口。
- [可靠性运维与 HA 演练](docs/operations.md)：消息治理、容量规划、自动化回归、故障切换与备份恢复。

## 项目范围

支付与冲正为模拟实现，管理员通过 API 操作，尚无完整管理后台和入场核验。默认部署用于本地运行；另提供 HA 演练拓扑，同机容器演练不能抵御宿主机故障。
