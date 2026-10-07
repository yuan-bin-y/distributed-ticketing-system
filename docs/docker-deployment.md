# Docker完整本地部署

## 这次完成了什么

根目录`compose.yml`一次启动六种Java服务和七个依赖：MySQL、Redis、RabbitMQ、Nacos、Tempo、Grafana、Prometheus。双击入口默认运行两个Order实例。另有一个只执行一次的Nacos配置导入容器，成功后显示Exited(0)是正常的。

- 六个服务各自有Dockerfile；镜像包含Java17和构建后的JAR。
- common、security、resilience、observability是代码依赖，已打进JAR，不单独启动。
- 容器之间使用mysql、redis、rabbitmq、nacos、auth等服务名，不使用Windows的localhost。业务调用通过Nacos发现容器实例。
- 五个业务库由JDBC自动建库、Flyway执行迁移；MySQL、Redis、RabbitMQ、Nacos和观测数据使用独立数据卷。
- 启动顺序通过健康检查控制：中间件→配置导入→Auth/Inventory→Event/Payment→Order→Gateway。
- JWT私钥和内部服务凭证只按需要挂载为只读文件，不复制进镜像。
- 自动导入deploy/nacos目录的七份配置，Group为TICKET_DOCKER；重新启动时保留Nacos已存在的配置。
- 启用MQ支付通知、活动缓存、网关限流、HTTP保护和OTLP追踪；Grafana自动连接Tempo。

这是一套**独立的新数据库**，不会自动搬运Windows的旧业务数据。第一次活动列表为空是正常的。它使用与旧项目不同的宿主机端口。

## 启动

### 双击启动（Windows）

在项目根目录直接双击：

| 文件 | 用途 |
| --- | --- |
| `启动项目.cmd` | 日常启动：复用已有JAR，运行两个Order实例 |
| `更新并启动.cmd` | 修改Java代码后使用：重新打包、构建镜像并更新服务 |
| `停止项目.cmd` | 停止并移除项目容器，保留全部数据卷 |

启动入口会尝试打开Docker Desktop并等待引擎就绪，随后调用已有PowerShell脚本。窗口会保留执行结果；看到“启动完成”后可以按任意键关闭窗口，容器继续后台运行。出错时窗口保留错误信息。

入口自动定位项目目录，路径可以包含空格；可以给这些CMD文件创建桌面快捷方式。使用PowerShell7时优先选择pwsh，未安装时使用Windows自带PowerShell5.1。执行策略Bypass只作用于本次子进程，不修改系统执行策略。

### 使用终端启动

先打开Docker Desktop，等待引擎运行。在项目根目录PowerShell执行：

```powershell
Set-Location 'E:\my project\distributed-ticketing-system'
./deploy/docker/start.ps1
```

脚本自动读取已设置的Windows `LOCAL_MYSQL_PASSWORD`，不需要在每个模块重复配置。新容器MySQL使用root账号。脚本会打包全部模块、构建镜像并等待服务可用。

已经完成Maven打包，或只想重新启动现有代码：

```powershell
./deploy/docker/start.ps1 -SkipPackage
```

修改Java代码后执行不带SkipPackage的启动命令。它会重新打包并更新容器。镜像版本使用local，已有基础镜像会复用；缺失基础镜像时才下载。

默认使用本机JDK17与Maven，其他电脑可以指定：

```powershell
./deploy/docker/start.ps1 -JdkHome 'D:\Java\jdk-17' -MavenCommand 'D:\maven\bin\mvn.cmd' -NacosHome 'D:\nacos'
```

Nacos镜像从官方3.1.2发行包的nacos-server.jar构建，默认发行包目录为`.local/nacos-3.1.2/nacos`。首次构建需保留这个包，脚本不复制原Nacos数据、用户和密码。

## 地址

| 服务 | Windows访问地址/端口 | 容器内端口 |
| --- | --- | --- |
| Gateway统一入口 | http://localhost:18060 | 8060 |
| Event / Inventory | 18061 / 18063 | 8061 / 8063 |
| Order | 经Gateway的18060访问，不固定映射宿主机端口 | 8062 |
| Payment / Auth | 18064 / 18065 | 8064 / 8065 |
| MySQL | localhost:13306 | 3306 |
| Redis | localhost:16379 | 6379 |
| RabbitMQ管理页面 | http://localhost:15680 | 15672 |
| RabbitMQ AMQP | localhost:15679 | 5672 |
| Nacos控制台 | http://localhost:18080 | 8080 |
| Nacos客户端地址 / gRPC | localhost:18848 / 19848 | 8848 / 9848 |
| Grafana | http://localhost:3002 | 3000 |
| Tempo查询 / OTLP HTTP | localhost:3201 / 4319 | 3200 / 4318 |

浏览器访问Gateway的`/api/auth/ping`可检查入口。业务API统一通过18060请求；直接访问其他服务仍遵守各自身份校验。

这套配置用于本机学习：所有映射绑定127.0.0.1；Nacos关闭登录鉴权，Grafana允许匿名Viewer，Redis未设密码。部署到远程服务器前需要另行配置访问控制。Nacos健康检查使用3.x接口，参见[Nacos官方监控手册](https://nacos.io/en/docs/latest/manual/admin/monitor/)。

## 管理员和本地文件

- 管理员账号：`docker_admin`。
- 管理员密码：`.local/docker/admin.password`，在IDEA中打开文件查看即可，不提交Git。
- RabbitMQ账号：`ticket`，密码：`.local/docker/rabbitmq.password`。
- JWT密钥：`.local/auth-keys/`；内部服务凭证：`.local/service-credentials/`。
- `.local/docker/nacos/`是准备好的Nacos镜像构建材料。
- `.local/docker/verification-result.json`保存验收标识和traceId，不保存登录密码或Token。

这些文件会复用，不会每次启动重新生成。不要随意删除密码文件：数据卷仍保留原账号密码。Windows变量修改也不会自动改变已经初始化的MySQL root密码，需要在数据库中正常修改。

## 查看、验收、停止

```powershell
# 查看运行状态
./deploy/docker/status.ps1

# 查看一个服务最近100行日志（可选gateway/auth/event/order/inventory/payment/nacos）
./deploy/docker/status.ps1 -Service order

# 真实交易验收，会新增一个用户、活动以及购买2张票的订单
./deploy/docker/verify.ps1

# 停止整套票务容器；保留所有数据卷
./deploy/docker/stop.ps1

# 再次启动，不必重新打包Java
./deploy/docker/start.ps1 -SkipPackage

# 检查原活动和完成订单在重新创建容器后仍存在
./deploy/docker/verify.ps1 -PersistenceOnly
```

verify检查：六个服务注册、管理员/用户登录、草稿库存准备和发布、重复下单幂等、398元模拟支付、MQ消费、两张电子票、重复支付不多出票、库存8/0/2、Outbox PUBLISHED、消费记录，以及Tempo中同一个traceId的MQ发送/消费Span和Grafana健康状态。

Grafana中打开Explore，选择Tempo，使用验收文件的traceId查询链路；这里的Grafana端口是3002，原来的3001页面属于另一套栈。

使用这套容器运行时，不必在IDEA运行同一套业务服务，也不必手动启动Windows Nacos、Redis和RabbitMQ。IDEA继续用于编辑和阅读代码。

## 两个Order实例

已加入正常停机等待与Order GET网络故障切换，详见[Order停机和读取重试](order-failover.md)。POST保持原来的幂等和业务恢复机制。

```powershell
# 扩容为两个Order；六个服务里的其他服务保持一个实例
./deploy/docker/start.ps1 -SkipPackage -OrderReplicas 2

# PowerShell7执行真实多实例与故障验收；结束时恢复两个Order
./deploy/docker/verify-multi-order.ps1

# 缩回一个Order
./deploy/docker/start.ps1 -SkipPackage -OrderReplicas 1
```

Order不再绑定Windows固定18062端口；两个容器各自使用8062，经Nacos注册、由Gateway选择实例。Tempo的service.instance.id是各容器HOSTNAME，可区分两个实例。启动脚本不指定OrderReplicas时默认一个实例。

验收先将8个同幂等键请求并发发到网关，用Tempo证明两个Order都处理过请求，并核对只有一个订单和一次预留。然后测试MQ支付与出票。最后暂时冻结B和Inventory，定向向A创建订单，观察其有效租约后强制停止A，立即恢复B和Inventory，让B在真实租约到期后接管，不直接修改业务表状态。验收完成后自动恢复两个Order。脚本会短暂影响本套票务栈，运行时不要同时进行其他购票操作。

注册信息与网关缓存需要收敛时间；强制停止实例后的短暂请求失败被记录在验收结果中，不承诺瞬时无损切换。结果文件为.local/docker/multi-order-result.json。

## 排查

- 启动失败：先查看脚本中最后一个报错服务，然后运行status.ps1 -Service对应名称。
- 端口占用：修改compose.yml里的端口变量，或关闭占用相同端口的演示容器；脚本只自动停止旧ticket-event-demo Compose项目。
- 认证失败：确认登录的是Docker的新数据库用户，Windows旧库用户不自动迁入。
- 外部IDEA服务若接入这套Nacos，要使用18848、Group TICKET_DOCKER，并保证其注册地址能从容器访问；默认建议六个服务一起使用容器。
- 不要执行down -v或volume prune来处理普通启动故障，它们可能删除数据。

后续可以用这套环境演示完整购票、查看Grafana链路，再学习扩容多个实例时的负载均衡与故障恢复。

本次实际运行结果见[Docker部署验收记录](docker-acceptance.md)。

## 热点库存与监控

Compose默认开启Redis票档并发准入，并启动Prometheus。Grafana打开`http://localhost:3002/d/ticket-platform-overview`；Prometheus目标与告警位于`http://localhost:19090/targets`及`/alerts`。服务管理端口9000仅在Compose网络内开放。

新增验证入口为`./deploy/docker/verify-hot-stock.ps1`与`./deploy/docker/verify-monitoring.ps1 -FaultTest`，详细实现与运行边界见[热点库存和指标告警](hot-stock-monitoring.md)。
