# 分布式票务交易平台

基于 Spring Cloud 的学习项目。第一版聚焦按票档抢票，验证微服务边界、高并发库存控制和跨服务交易一致性。

项目设计与实施顺序见 [设计文档](docs/design.md)。当前已建立网关和活动服务的最小启动模块，接入 Nacos 注册发现，并加入活动服务的 MySQL 数据源与 Flyway 建表迁移；活动查询与交易功能按后续步骤实现。

## 第一版目标

用户能浏览活动与场次，在开售后购买指定票档，完成模拟支付并取得电子票；未支付订单到期后自动取消并释放库存。管理员能维护活动、场次、票档与库存。

重点验收：并发下不超卖、同一用户不能重复购买同一场次、重复请求和消息不会重复扣库存或出票、失败和超时后资源最终得到正确处理。

## 当前工程与启动

父工程负责聚合和依赖版本管理，两个子模块各自拥有启动类、配置和可执行 JAR。Gateway 使用 WebFlux/Netty，活动服务使用 Spring MVC/Tomcat；活动服务的测试接口为 `GET /api/events/ping`。

要求 JDK 17 或更新版本、Maven 3.9。先在项目根目录构建（命令中的本地仓库与本项目验证使用的仓库一致）：

```powershell
# 本机已安装的 JDK 17；其他机器改为实际 JDK 路径。
$env:JAVA_HOME = 'C:\Users\RE\.jdks\ms-17.0.20'
mvn "-Dmaven.repo.local=$PWD/target/.m2" package
```

先按 [Nacos 3.x 官方入门文档](https://nacos.io/docs/v3.1/quickstart/quick-start/) 启动单机 Nacos。两个应用默认连接 `127.0.0.1:8848`，客户端还需要能访问其 gRPC 端口 `9848`。Nacos 3.x 的控制台通常位于 `http://localhost:8080/index.html`，不是应用配置中的服务地址。当前代码只接入注册发现，还没有接入配置中心。

若 Nacos 地址不同，在启动两个应用的窗口中设置相同的 `NACOS_SERVER_ADDR`。若开启了 Nacos 客户端鉴权，还需设置 `SPRING_CLOUD_NACOS_DISCOVERY_USERNAME` 和 `SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD`，使用该 Nacos 实例的账号密码。

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

IntelliJ IDEA 可重新加载根 Maven 工程，将 Project SDK 和 Maven Runner JRE 设为 JDK 17，分别运行 `TicketEventApplication`、`TicketGatewayApplication`。停止命令行服务使用各窗口的 Ctrl+C。

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
