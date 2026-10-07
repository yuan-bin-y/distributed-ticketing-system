# 六服务配置中心、公共配置与动态刷新

六个独立服务都接入Nacos配置中心，先加载公共文件，再加载服务专属文件。Event的缓存TTL和日志级别支持动态生效；其他启动时构造的组件仍须重启。服务注册与发现继续按原配置运行，公共Java支持模块不启动、不接入配置客户端。

## 启用

1. 重新加载 Maven，启动 Nacos。
2. 在Nacos「配置管理 → 配置列表」创建公共文件和各服务专属文件：Namespace `public`、Group `TICKET_GROUP`、格式YAML。
3. 内容复制 [配置样例目录](../deploy/nacos/)。已有同名配置时仅加入所需字段，不覆盖其他内容。
4. 六服务各自IDEA启动配置的 **Active profiles 填 `nacos`**；或设置 `SPRING_PROFILES_ACTIVE=nacos`，再启动服务。

| Data ID | 读取它的服务 |
| --- | --- |
| ticket-common.yaml | 六服务，共用日志级别与启动时采样率 |
| ticket-auth-service.yaml | Auth，日志和凭证有效期 |
| ticket-gateway.yaml | Gateway，日志和限流参数 |
| ticket-event-service.yaml | Event，日志和动态缓存TTL |
| ticket-order-service.yaml | Order，日志和下游调用保护并发额度 |
| ticket-inventory-service.yaml | Inventory，本阶段仅日志 |
| ticket-payment-service.yaml | Payment，日志和Order调用保护额度 |

如仅启用Event，至少创建 `ticket-common.yaml` 与 `ticket-event-service.yaml` 两份配置。

```yaml
ticket:
  event-cache:
    ttl-ms: 60000
```

各服务使用自己的 `application-nacos.yml` 与 `spring.config.import` 加载并监听两份远程YAML。未启用profile时继续使用本地配置，原隔离验证无需连接配置中心。远程导入使用必需模式；启用时先创建配置并确保Nacos可达。

同名键覆盖关系是：本地application配置 → 导入公共配置 → 后导入服务配置；命令行指定值优先于远程配置。这里的顺序已经用各服务实际资源验证，不使用bootstrap.yml。

| 环境变量 | 默认值 |
| --- | --- |
| NACOS_SERVER_ADDR | 127.0.0.1:8848 |
| NACOS_CONFIG_NAMESPACE | 空，表示 public |
| NACOS_CONFIG_GROUP | TICKET_GROUP |
| TICKET_COMMON_CONFIG_DATA_ID | ticket-common.yaml |
| TICKET_SERVICE_CONFIG_DATA_ID | 当前服务名.yaml |
| NACOS_USERNAME / NACOS_PASSWORD | 空；开启配置中心认证时填写 |

Event继续兼容旧EVENT_CONFIG_GROUP / EVENT_CONFIG_DATA_ID；统一变量优先。自定义命名空间填写ID，而非显示名。多profile可以用逗号分隔。密码、数据库凭证和签名私钥继续使用环境变量或本机文件。

## 哪些配置可以动态生效

| 配置 | 修改后怎么生效 |
| --- | --- |
| logging.level.* | Spring Cloud刷新日志系统，不需要重启 |
| ticket.event-cache.ttl-ms | Event自定义监听，后续缓存写入使用新TTL |
| management.tracing.sampling.probability | 当前采样器在启动时创建，重启对应服务 |
| Auth access-ttl / refresh-ttl | 当前参数对象在启动时绑定，重启Auth |
| Gateway限流额度 | 当前限流组件保存启动快照，重启Gateway |
| HTTP熔断和并发隔离参数 | 当前保护器在启动时构造，重启调用方服务 |

Nacos通知与Spring环境更新，不等于已构造的组件自动重建。当前不对这些组件统一添加RefreshScope，以免重建信号量或保护器时影响在途调用。下一阶段若扩展动态治理，需为每种组件设计更新过程并验证。

## 修改后如何生效

```text
Nacos发布配置 → 客户端通知 → Spring更新Environment
    → EnvironmentChangeEvent → EventCacheTtl校验并更新volatile字段
    → EventQueryCache下一次写入读取最新TTL
```

`EventCacheTtl` 是普通单例，不重建缓存组件或信号量。写入时读取一次TTL快照，原有0至20%的随机抖动继续保留。例如基础60000ms，对应实际写入60000至71999ms。

- 有效范围：100至86400000ms，必须为整数。
- 启动时非法配置：启动失败，避免错误参数进入服务。
- 运行中非法配置：日志记录拒绝，保留上次有效值。
- 正常回滚：发布之前的数值，后续写入使用恢复后的TTL。
- 已有Redis缓存：不修改剩余寿命；命中缓存不会重置TTL。
- 空结果缓存：继续使用原来的固定5000ms。

## 代码入口

| 文件 | 改动 |
| --- | --- |
| 六服务pom.xml | Nacos Config starter，与Discovery独立 |
| 六服务application.yml | 默认关闭Config客户端与导入检查 |
| 六服务application-nacos.yml | profile开启客户端，先公共、后专属导入与监听 |
| cache/EventCacheTtl.java | 初始校验、监听变化、保留最后有效值 |
| cache/EventQueryCache.java | 写入时读取最新TTL，不改变重建锁逻辑 |
| deploy/verify/EventConfigVerification.java | 真实Nacos/Redis刷新与回滚验证 |
| deploy/verify/ServiceConfigVerification.java | 各服务依赖与资源验证、真实日志系统检查 |

## 验证

2026-10-07：全模块构建成功，真实Nacos和Redis通过21项Event检查。覆盖公共配置导入、远程TTL覆盖本地默认值、运行时刷新、Redis新键TTL变化、缓存对象不重建、旧键不延寿、负数/非数字/超大值拒绝，以及无需重启的回滚。

六服务配置验证另通过90项检查（每服务15项）：分别使用其打包依赖和实际application资源，核对服务专属覆盖公共、公共属性继承、命令行优先级、公共日志级别更新不覆盖服务专属值，以及日志DEBUG/INFO刷新和回滚。测试加载最小配置上下文，不启动数据库或业务Bean；Gateway使用随机本机端口的响应式上下文，结束关闭，不属于多实例业务故障测试。

验证使用唯一Group、Data ID和Redis前缀，结束清理自身配置与缓存，不覆盖正式配置。这里的验证仅创建缓存组件，没有启动完整业务数据库或新增接口。

```powershell
& deploy/verify/verify_event_config.ps1
& deploy/verify/verify_service_config.ps1
```

日常手动验证：先查询一个已发布活动，查看对应Redis键PTTL；在Nacos将60000改成120000并发布，再查询之前未缓存的活动或分页条件，观察新键约120至144秒。已缓存键继续沿用原过期时间。

接入方式参考[Spring Cloud Alibaba 2025.x官方配置指南](https://sca.aliyun.com/docs/2025.x/user-guide/nacos/advanced-guide/)。本项目使用config.import，不使用bootstrap.yml。

日志更新机制参考[Spring Cloud Environment Changes](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/application-context-services.html)。
