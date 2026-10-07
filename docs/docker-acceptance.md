# Docker部署验收记录

验收日期：2026-10-07。环境：Windows、Docker Desktop Linux引擎、JDK17，Compose项目ticket-platform。使用本机缓存的基础镜像，Nacos3.1.2镜像由本机官方发行包构建。

## 执行结果

| 项目 | 实测结果 |
| --- | --- |
| Maven全部模块package | 成功，构建脚本跳过单元测试；本次验证为真实容器交易验收 |
| 六个业务镜像与Nacos镜像 | 构建成功 |
| 运行服务 | 六个Java服务与六个依赖运行，Java服务健康探针全部通过 |
| Nacos服务发现 | TICKET_DOCKER组包含六个健康服务 |
| Nacos配置初始化 | 七份配置导入成功；重建后全部显示Keep existing config |
| 用户与管理员认证 | 网关真实登录成功 |
| 活动准备和发布 | 草稿库存准备READY，发布后公开查询成功 |
| 重复下单 | 同幂等键返回相同订单，数据库订单数量1 |
| 支付出票 | 199元×2，总金额398元，订单COMPLETED，电子票2张 |
| 重复模拟支付 | 仍然只有2张电子票 |
| 库存核对 | 10张初始库存→可用8、预留0、售出2 |
| MQ可靠通知 | Outbox PUBLISHED一条，Order消费记录一条 |
| Tempo | 同一traceId包含payment.succeeded publish与payment.succeeded consume |
| Grafana | /api/health返回database=ok |
| 容器删除重建 | stop.ps1后start.ps1 -SkipPackage，原订单COMPLETED、原活动可查询、七份配置与Tempo链路保留 |
| 脚本检查 | PowerShell脚本语法检查通过，git diff --check通过 |

首次验收记录：

- 活动ID：1，票档ID：1。
- 订单编号：3e00306fe34a4064a9ef90bf6cc02a69。
- 支付编号：7e7ccf542084466c8ab417f14a900729。
- traceId：ff59e54b292513ab910fdd2eeeb9f092。

这些是独立Docker库中的演示数据。每次完整verify会新建演示数据，最新结果位于.local/docker/verification-result.json；记录不包含密码、Token和私钥。

## 修复的部署问题

- 复用已有eclipse-temurin:17-jre-jammy，避免缺失基础镜像标签导致访问Docker Hub超时。
- 为数字UID运行的Java进程明确user.home，避免Nacos默认日志路径出现问号及业务客户端缓存不可写。
- Nacos3使用nacos.server.main.port设置服务端口，控制台单独8080；健康检查改为v3 readiness接口。
- 保留管理API兼容供本地七份配置初始化脚本使用，Java服务通过gRPC连接。
- 关闭没有接收端的OTLP metrics导出，Trace正常导出到Tempo。

以上记录为首次单实例验收。随后完成了下面的双实例验收；本次没有重新进行吞吐压测。操作步骤见[Docker完整本地部署](docker-deployment.md)。

## Order双实例验收

同日执行deploy/docker/verify-multi-order.ps1，结果PASS。

| 项目 | 实测结果 |
| --- | --- |
| 启动与注册 | Order两个容器健康，Nacos两个健康实例 |
| 实际负载分配 | 8个并发网关下单请求，Tempo资源标识证明5cd9a9e8f422、e3a882ad13dd两个实例均处理请求 |
| 并发幂等 | 相同用户/幂等键返回同一订单，订单1条、预留1条 |
| 双实例支付 | MQ消费记录1条，订单COMPLETED，电子票2张 |
| 故障注入 | 暂时冻结B与Inventory，定向请求A，观察STOCK_PENDING的有效租约，然后强制停止A并立即恢复B与Inventory |
| 接管恢复 | B等待真实60秒租约到期，原订单转为PENDING_PAYMENT；经网关付款后COMPLETED、电子票1张，预留仍只有1条 |
| 故障后的请求 | 注册与网关实例缓存收敛期间出现3次查询失败，脚本记录并在120秒内继续检查，随后成功 |
| 库存 | 两个案例共买3张，最终可用7、预留0、售出3 |
| 验收后状态 | 自动恢复两个Order容器，两个健康实例重新注册 |

活动/票档ID：3；并发订单83a44495dde842b7a0f32afce76ef823；接管订单c64823fd35a248f990998f61bd4b06e5。结果保存在.local/docker/multi-order-result.json，不包含凭证。

第一次故障验收因查询恰好命中网关尚未更新的实例地址而中断；修正的是验收脚本的有界等待逻辑，没有修改订单业务逻辑。本次证实最终接管与幂等正确，不能据此声称强制停机时所有请求都无损成功。

## 优雅停止与读取故障切换（2026-10-08）

加入Order显式优雅停止配置，以及Gateway的OrderReadRetryFilter后重新构建、部署、执行完整双实例验收，结果PASS。

| 项目 | 实测结果 |
| --- | --- |
| 重试边界测试 | 5项通过：网络失败换实例、POST不重试、业务状态不重试、失败地址不再次选择、总预算取消挂起请求 |
| 正常停止 | 确认真实GET被表锁阻塞后发送SIGTERM；在途GET成功返回，12.86秒后优雅退出 |
| 冻结仍注册的实例 | 6次查询全部成功；日志证明实际换实例重试3次；客户端最长耗时3670ms |
| 强制停机后立即查询 | 6次全部成功；实际换实例重试2次；客户端最长耗时3707ms |
| 等待后台恢复的查询 | 失败0次；之前一次验收为3次，但样本/时机不同，不作为统计性能对照 |
| 业务正确性 | 8个并发请求生成1个订单/1次预留；停止租约持有者后另一实例接管；两个案例出票共3张，库存7/0/3，MQ记录核对通过 |
| 最终状态 | 两个Order健康实例均已恢复 |

活动/票档ID5；并发订单093aa0cc18ac49fd8df549d4e3c5664d；故障接管订单da17e8390e9345fd90aa07bf17d091df。对应运行记录.local/docker/read-retry-acceptance.log；结构化记录.local/docker/multi-order-result.json。PowerShell语法检查与git diff --check通过。

第一次强制停止检查6次查询已成功，但未观察到重试；因此增加冻结仍在注册列表中的实例作为可复现超时场景。最终验收同时覆盖冻结和强制停机，二者均观察到真实换实例日志。没有增加生产测试接口、没有直接改写订单状态或租约。

这些结果仅代表本次本机双实例样本。读取保护不保证所有实例失效时请求成功，也不对写请求自动重试。实现和参数边界见[Order停机和读取重试](order-failover.md)。
