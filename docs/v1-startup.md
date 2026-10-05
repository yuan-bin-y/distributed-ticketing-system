# 第一版启动指南

以下步骤用于本地 Windows 演示，所有命令从项目根目录 `E:\my project\distributed-ticketing-system` 执行。IDEA 和命令行均使用这个工作目录，保证相对密钥、凭证路径一致。

## 环境要求

| 依赖 | 本项目环境 | 用途 |
| --- | --- | --- |
| Java | JDK 17 | 编译、生成开发密钥、运行应用 |
| Maven | 3.9 | 聚合构建与依赖管理 |
| MySQL | 本机 8.0.39，至少 8.0.16 | 业务数据、本地事务和 Flyway 迁移 |
| Redis | 本机 6379 | 登录会话；当前本机 3.2 为已验证开发环境 |
| Nacos | 本机 3.1.2 | 注册发现；8848 和客户端 gRPC 9848 |

先启动 MySQL、Redis，再启动 Nacos。本机 Nacos 位于项目的 `.local/nacos-3.1.2/nacos`，如果已运行则直接使用；新机器需要另行准备 Nacos 发行包及服务端配置。

本机手动启动 Nacos 可在 PowerShell 执行其自带脚本：

```powershell
$env:JAVA_HOME = 'C:\Users\RE\.jdks\ms-17.0.20'
Set-Location 'E:\my project\distributed-ticketing-system\.local\nacos-3.1.2\nacos\bin'
.\startup.cmd -m standalone
```

控制台为 `http://localhost:8080/index.html`。控制台登录与业务 JWT 无关；微服务配置使用服务端地址 8848。检查基础依赖：

```powershell
3306,6379,8848,9848 | ForEach-Object {
    [pscustomobject]@{Port=$_;Listening=(Test-NetConnection 127.0.0.1 -Port $_ -WarningAction SilentlyContinue).TcpTestSucceeded}
}
```

端口可连接只是初步检查，实际数据库连接、认证和注册还需看各服务启动结果。

## 数据库与通用环境变量

沿用 Windows 用户变量 `LOCAL_MYSQL_USERNAME`、`LOCAL_MYSQL_PASSWORD`。设置后彻底退出再打开 IDEA；运行配置中的同名变量优先，避免旧配置覆盖。账号需有相应访问、建表权限，自动建库还需建库权限。

默认各服务的 JDBC 地址带 `createDatabaseIfNotExist=true`，驱动创建不存在的库，Flyway 执行该服务的迁移。不要改已经执行的 SQL 版本文件。已有旧 Order 实例升级时先全部停止，再由新实例执行包括 V5 限购回填在内的迁移。

| 可选变量 | 默认值或用途 |
| --- | --- |
| NACOS_SERVER_ADDR | 127.0.0.1:8848 |
| LOCAL_REDIS_HOST / LOCAL_REDIS_PORT | 127.0.0.1 / 6379 |
| LOCAL_REDIS_PASSWORD | 默认空；有密码时填写 |
| AUTH_DB_URL / EVENT_DB_URL / ORDER_DB_URL / INVENTORY_DB_URL / PAYMENT_DB_URL | 覆盖各服务完整 JDBC 地址 |
| AUTH_JWK_SET_URI | 校验端默认从 http://127.0.0.1:8065/.well-known/jwks.json 获取公钥 |

若 Nacos 开启客户端鉴权，各服务还需配置 `SPRING_CLOUD_NACOS_DISCOVERY_USERNAME` 和 `SPRING_CLOUD_NACOS_DISCOVERY_PASSWORD`。如果改变 Auth 端口，同步调整校验端公钥地址。

## 首次准备密钥与凭证

只对缺失的文件执行生成命令；生成器拒绝覆盖已有文件。已有本机文件直接复用，重启不需要重新生成。

```powershell
Set-Location 'E:\my project\distributed-ticketing-system'
$javaExe = 'C:\Users\RE\.jdks\ms-17.0.20\bin\java.exe'
& $javaExe deploy/auth/GenerateAuthKeys.java
& $javaExe deploy/auth/GenerateServiceCredential.java .local/service-credentials/order-event.token
& $javaExe deploy/auth/GenerateServiceCredential.java .local/service-credentials/order-inventory.token
& $javaExe deploy/auth/GenerateServiceCredential.java .local/service-credentials/event-inventory.token
& $javaExe deploy/auth/GenerateServiceCredential.java .local/service-credentials/order-payment.token
& $javaExe deploy/auth/GenerateServiceCredential.java .local/service-credentials/payment-order.token
```

Auth 使用 `.local/auth-keys/private.pem` 和 `public.pem`；其他服务通过 JWKS 获取公钥。每个调用方向双方读取同一份对应服务凭证，五个方向使用不同文件。跨主机部署须分别分发必要文件，配置绝对路径与 TLS；本指南针对单机开发。

`.local` 不进入 Git，复制源码不包含这些文件。完整凭证路径及环境变量见 [内部服务身份](service-identity.md)、[支付身份接入](payment-auth.md)、[活动管理](event-administration.md)。

## 构建与 IDEA 启动

```powershell
Set-Location 'E:\my project\distributed-ticketing-system'
$env:JAVA_HOME = 'C:\Users\RE\.jdks\ms-17.0.20'
& 'D:\develop\apache-maven-3.9.14-bin\apache-maven-3.9.14\bin\mvn.cmd' "-Dmaven.repo.local=$PWD/target/.m2" package
```

其他机器替换实际 Java/Maven 路径。IDEA 重新加载根 `pom.xml`，Project SDK、Maven Runner 和运行配置均选择 Java 17。Application 或 Spring Boot 配置都可启动 main；每份配置需核对模块、主类、工作目录和环境变量。

推荐依次启动以下服务，Auth 公钥与会话依赖先就绪，Gateway 最后启动：

| 顺序 | 模块及默认端口 | 主类 |
| --- | --- | --- |
| 1 | ticket-auth-service 8065 | com.byy.ticket.auth.TicketAuthApplication |
| 2 | ticket-inventory-service 8063 | com.byy.ticket.inventory.TicketInventoryApplication |
| 3 | ticket-event-service 8061 | com.byy.ticket.event.TicketEventApplication |
| 4 | ticket-payment-service 8064 | com.byy.ticket.payment.TicketPaymentApplication |
| 5 | ticket-order-service 8062 | com.byy.ticket.order.TicketOrderApplication |
| 6 | ticket-gateway 8060 | com.byy.ticket.gateway.TicketGatewayApplication |

Auth 首次演示创建管理员时，在 **Auth 运行配置**设置 `AUTH_BOOTSTRAP_ADMIN_USERNAME`、`AUTH_BOOTSTRAP_ADMIN_PASSWORD`，使用自己选择的账号密码。初始化成功后可移除这两个变量，原账号继续登录；同名普通用户或密码不匹配会拒绝启动，不会覆盖。规则见 [活动管理](event-administration.md)。

在 **Payment 运行配置**设置 `PAYMENT_SIMULATION_ENABLED=true` 才能演示模拟支付与冲正；默认关闭。保持 `ORDER_DEV_IDENTITY_ENABLED` 和 `PAYMENT_DEV_IDENTITY_ENABLED` 为默认 false，演示使用真实登录 Token。

命令行启动示例，在项目根目录的独立窗口执行：

```powershell
& 'C:\Users\RE\.jdks\ms-17.0.20\bin\java.exe' -jar ticket-order-service/target/ticket-order-service-1.0-SNAPSHOT.jar
```

其他服务替换模块目录及同名 JAR。每个窗口的临时变量只影响该窗口启动的进程。停止命令行服务用 Ctrl+C；IDEA 用 Stop。

## 启动完成检查

1. Nacos 服务列表显示六个服务，健康实例至少各一个。公共模块不会出现。
2. 浏览 `http://localhost:8060/api/events`，应返回 `code=OK`；新库无活动时列表为空。
3. Auth 的 `/api/auth/ping`、Event 的 `/api/events/ping` 可辅助检查路由。Order、Payment 的公共请求需要 Token，不把匿名 401 当作启动失败。
4. 用 [演示步骤](v1-demo.md) 完成一次交易；ping 成功不能证明全部依赖及交易正确。

## 常见启动与演示问题

| 现象 | 核对位置 |
| --- | --- |
| 连接不到 Nacos或没有可用实例 | 8848、9848、地址、客户端鉴权、服务日志与分组 |
| 数据库认证失败 | 实际运行配置的变量及覆盖关系，确认数据库账号，不以 IDEA 数据源成功替代应用配置 |
| 找不到密钥或服务凭证 | 工作目录、默认文件是否存在、指定路径；不要靠删除并重新生成原密钥处理 |
| JWT/JWKS 或 Redis 校验失败 | Auth 已启动、公钥地址一致、Redis 地址与密码正确、退出后的旧 Token 已失效 |
| 模拟支付被拒绝 | Payment 的模拟开关、订单归属、支付期限 |
| 8060 或其他端口占用 | 停止自己的旧实例，或协调修改对应服务端口；不要重复启动两套同端口配置 |
| 草稿无法发布 | 票档 preparationStatus 是否全为 READY；查看 lastError，按原参数 prepare |
| 下单返回 202 | 保存 orderNo，查询进度；结果不确定时保留原幂等键，不能改键盲目再下单 |
