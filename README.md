# 分布式票务交易平台

基于 Spring Cloud 的个人学习项目。第一版后端已完成活动发布、库存预留、下单、模拟支付、电子票与故障恢复闭环，并归档功能验收和单机并发测试。第二版继续加入支付MQ、活动缓存与网关限流。

## 第一版交付入口

**从 [第一版交付说明](docs/v1-delivery.md) 开始阅读。**

| 资料 | 用途 |
| --- | --- |
| [启动指南](docs/v1-startup.md) | 本地依赖、Windows变量、密钥、服务凭证、构建与IDEA启动 |
| [API演示](docs/v1-demo.md) | 管理员发布、用户下单、重复提交、模拟付款、出票及退出 |
| [Apifox导入文档](docs/apifox/README.md) | 22个公开接口、13个内部接口，认证配置与变量提取脚本 |
| [架构与流程](docs/v1-architecture.md) | 服务关系图、完整交易顺序、事务和身份边界 |
| [技术栈与简历描述](docs/v1-resume.md) | 学习重点、项目介绍和可引用的实测数据 |
| [功能验收记录](docs/v1-acceptance.md) | 312项功能与故障检查、复现方式 |

## 第二版进度

已加入[六服务Nacos配置中心与动态刷新](docs/nacos-config.md)：启用 `nacos` profile 后加载公共与服务专属配置，支持日志级别及Event缓存TTL动态生效。其他启动快照参数明确标注为重启生效；六服务配置90项、真实Nacos/Redis的TTL刷新与回滚21项检查通过。

已接通 [全项目分布式追踪](docs/http-tracing.md)：六服务 HTTP、支付 Outbox、MQ 发布/消费、重试与后台库存确认及出票均可关联到 Tempo，Grafana 展示真实父子调用与耗时。完整链路561项、MQ追踪与故障226项检查通过；开启方式与代码入口见文档。

已接通[支付MQ与订单消费](docs/payment-mq.md)：付款成功与[Outbox事件](docs/payment-outbox.md)同事务保存，发送器等待Confirm并检查Return；订单回查付款事实，消费记录与付款依据同事务提交后ACK。支持延迟重试、死信查看及确认重投。默认保留HTTP模式，设置 `TICKET_MQ_ENABLED=true` 开启MQ链路。

已加入[活动缓存、热点重建与网关限流](docs/event-cache-rate-limit.md)：展示查询缓存、跨实例重建锁、发布提交后失效、受控回源，以及响应式 Redis 令牌桶。缓存和限流默认启用；[压测记录](docs/performance/cache-rate-2026-10-07.md)包含查询次数、延迟和限流结果。

已接入[全项目业务HTTP调用保护](docs/http-resilience.md)：Order到Event/Inventory/Payment、Event到Inventory及Payment到Order均使用Resilience4j熔断与并发隔离。各下游独立统计，保护拒绝不发送HTTP，保留原有写请求幂等及事实核对。默认开启，各链路可分别关闭；[Event入口说明](docs/event-resilience.md)保留原配置阅读顺序。

## 工程结构

Java 17、Spring Boot 4.0.8、Spring Cloud 2025.1.3、Spring Cloud Alibaba 2025.1.0.0、MyBatis-Plus 3.5.17。完整版本由父 `pom.xml` 管理。

| 模块 | 默认端口 | 数据所有权 |
| --- | --- | --- |
| ticket-gateway | 8060 | 统一路由和响应式身份准入，无业务数据库 |
| ticket-event-service | 8061 | 活动、场次、票档、发布及库存准备进度 |
| ticket-order-service | 8062 | 订单、价格快照、累计限购、履约进度、消费记录和电子票 |
| ticket-inventory-service | 8063 | 实际库存及预留记录 |
| ticket-payment-service | 8064 | 模拟支付事实、Outbox事件、通知进度及模拟冲正 |
| ticket-auth-service | 8065 | 用户、凭证签发与Redis会话 |
| ticket-common | 不启动 | Result、异常、traceId等基础支持 |
| ticket-resilience | 不启动 | 通用HTTP熔断、并发隔离与策略绑定 |
| ticket-security | 不启动 | 用户认证与服务凭证支持 |
| ticket-observability | 不启动 | 标准追踪上下文捕获、持久化恢复及异步 Span 支持 |

第一版五个独立业务库共12张业务表；当前第二版新增支付Outbox和订单消费记录，共14张业务表，另有各库Flyway迁移历史表。服务只访问自己的库，通过HTTP协作；启用MQ时付款通知使用RabbitMQ。Gateway采用WebFlux，业务服务采用Spring MVC。

## 正确性与性能结果

功能验收已通过：真实Nacos/Gateway 80项、累计限购95项、支付履约与恢复137项。涵盖完整交易、权限、重复请求、响应丢失、服务重启、超时关闭与迟到付款冲正。

- [性能基线](docs/performance/baseline-2026-10-05.md)：16～256工作线程，单热点票档约60～65下单请求/秒；核对库存不足、重复请求及支付出票结果。
- [性能定位](docs/performance/profiling-2026-10-05.md)：默认关闭的分段耗时和连接池观测，定位热点库存行竞争及获取连接排队。
- [对照实验](docs/performance/comparison-2026-10-06.md)：两轮共8,000次下单最终预留成功；单热点增加连接收益有限，同场次十票档分散请求约152请求/秒。

以上是本机有限请求测试，详见环境、延迟及最终状态核对记录；不代表生产容量或完整支付出票吞吐。

## 代码说明索引

| 内容 | 文档 |
| --- | --- |
| 整体设计与实施记录 | [设计文档](docs/design.md) |
| 注册登录、刷新和退出 | [Auth服务](docs/auth-service.md) |
| 网关响应式认证 | [网关认证](docs/gateway-auth.md) |
| 订单用户身份 | [订单身份](docs/order-auth.md) |
| 支付身份与双向凭证 | [支付身份](docs/payment-auth.md) |
| Event和Inventory内部凭证 | [内部服务身份](docs/service-identity.md) |
| 活动草稿、库存初始化及发布 | [活动管理](docs/event-administration.md) |
| 订单事务、库存协作及恢复 | [订单流程](docs/order-workflow.md) |
| 用户与场次累计限购 | [累计限购](docs/purchase-quota.md) |
| 支付事实与模拟冲正 | [支付服务](docs/payment-service.md) |
| 从订单创建支付单 | [发起支付](docs/order-payment-create.md) |
| 可靠通知与付款依据回查 | [付款通知](docs/payment-notification.md) |
| 成交及迟到付款恢复 | [履约与冲正](docs/payment-fulfillment.md) |
| 唯一电子票和本人查询 | [出票](docs/ticket-issuance.md) |

## 当前范围

第一版通过API演示，没有业务前端；支付与冲正为模拟，电子票没有入场核验。管理员支持创建完整草稿、查看准备、原参数重新准备和发布，暂不支持编辑已初始化参数、下架或调整库存。

一致性使用各服务本地事务、持久化进度、幂等、核对、重试与补偿，支付MQ链路使用Outbox与消费幂等。Nacos配置中心已接入；多实例故障与多机容量验证留待后续。
