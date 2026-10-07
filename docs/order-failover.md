# Order停机、读取重试和恢复

## 配置和代码

- Order的application.yml：server.shutdown=graceful；每个关闭阶段最多等待20秒。Compose保持30秒停止宽限。正常停止让在途HTTP完成，强制杀进程无法执行优雅退出。
- Gateway的application.yml：ticket.order-read-retry.enabled=true、max-retries=2、attempt-timeout=1500ms、total-timeout=5s。可用ORDER_READ_RETRY_ENABLED=false关闭，修改后重启Gateway。
- OrderReadRetryFilter.java：标准LoadBalancer选择首次目标后，处理Order GET的网络故障。失败后从ReactiveDiscoveryClient读取候选，排除同一请求中已失败的host:port，再尝试另一个实例。
- verify-order-graceful.ps1：由多实例验收加载；短暂持有数据库表锁，确认真实GET正在查询，然后正常停止Order并检查请求返回。没有新增生产测试接口、没有修改订单状态或金额。
- verify-multi-order.ps1：增加正常在途停止检查及强制停止后立即发出的6次GET检查，并继续核对真实租约接管、付款、MQ和出票。故障检查结束时恢复两个Order。

另有冻结实例检查：暂时暂停仍在注册列表里的Order，对网关发送6次查询，要求出现真实order.read.retry日志且查询全部成功，然后恢复该实例。强制停止后发现信息可能很快收敛，不能仅凭“请求成功”推断发生过重试。

过滤器顺序10151，在ReactiveLoadBalancerClientFilter之后、Netty发送请求之前；重试不重新执行入口认证和限流。响应尚未提交时才允许网络失败重试，已提交响应不重新发送。

## 重试边界

只针对路由ticket-order-service的GET。IOException、超时及其cause属于可恢复网络故障。HTTP 401/403/404/业务5xx不自动重试。POST下单、发起支付、管理员发布等写请求不经过这个机制。

一次请求最多初次发送加两次重试，最多尝试三个不同实例；只有两个实例时，各实例最多尝试一次。找不到其他候选时返回503，总路由预算耗尽返回504。每次路由尝试最多1500ms，总路由预算5秒；预算从该过滤器开始，不包含前置身份验证，后续已经开始的响应体发送也不能回滚重试。

Nacos本地快照可能暂时保留停止实例；失败地址集合解决同一次查询再次挑中已失败实例的问题。初次查询仍可能选中停止实例并付出一次连接失败或超时成本。所有实例均不可用时仍会失败，不能保证所有请求零失败。

GET重试日志order.read.retry包含前后实例地址和重试次数，MDC保留traceId；不打印Token和请求正文。下单超时仍沿用原幂等键重新提交，由唯一键和库存幂等核对。读取保护不能替代付款事实、任务领取租约和业务补偿。

## 运行验收

先通过start.ps1更新镜像，再执行PowerShell7验收：

```powershell
./deploy/docker/start.ps1 -OrderReplicas 2
./deploy/docker/verify-multi-order.ps1
```

脚本产生演示用户、活动和两个订单，并短暂冻结Inventory及一个Order。运行时不要同时进行其他业务操作。结果保存在.local/docker/multi-order-result.json，包含正常退出时间、立即GET失败数/换实例次数/最长耗时，以及后台接管期间的查询失败次数。

读取网络重试原理参考[Spring Cloud Gateway 5.0.3 Retry实现](https://github.com/spring-cloud/spring-cloud-gateway/blob/v5.0.3/spring-cloud-gateway-server-webflux/src/main/java/org/springframework/cloud/gateway/filter/factory/RetryGatewayFilterFactory.java)。本项目另外实现按失败地址避让，没有启用通用的5xx/POST Retry。
