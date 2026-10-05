# 下单链路性能定位

## 结论与证据

本机当前热点票档场景的主要等待在Inventory：库存行锁竞争占用连接，后续请求排队获取连接。Event规则查询耗时很小。该结论基于两轮分段复测、连接池采样和数据库等待观测；尚未定位锁持有期间提交、磁盘刷新等底层耗时的具体来源。

第二轮运行ID：`53907c6f24ec495bab04c6f989381342`，直接接入Hikari原生计时。每档1,000次独立下单，64个真实用户，真实Gateway/Nacos，配置沿用前一轮基线。

| 并发 | 请求/秒 | HTTP P95 ms | 最终成功预留 |
| --- | --- | --- | --- |
| 16 | 61.1 | 309 | 1,000 |
| 64 | 61.8 | 1,157 | 1,000 |
| 256 | 65.5 | 4,108 | 1,000 |

以下是每档各1,000个作用域样本的**平均耗时**，单位ms：

| 阶段 | 16并发 | 64并发 | 256并发 |
| --- | --- | --- | --- |
| Order调用Event查询规则 | 5.02 | 4.35 | 4.63 |
| Order创建订单/快照/额度的本地事务 | 19.71 | 22.45 | 60.01 |
| Order调用Inventory预留HTTP | 192.67 | 931.62 | 2,580.39 |
| Inventory事务，含获取连接与提交 | 189.40 | 928.86 | 2,577.54 |
| Inventory实际获取连接耗时 | 27.28 | 769.08 | 2,426.71 |
| Inventory库存条件更新SQL | 143.37 | 141.53 | 134.09 |

这些计时互相嵌套，不能全部相加：库存HTTP包含库存事务，事务包含获取连接与SQL。Order Impl总耗时也不包含网关、Token校验及进入Controller前的排队。

256并发下，Inventory获取连接约占事务平均耗时94%。连接借出后平均持有约150.28ms；其中库存更新SQL约134.09ms。数据库等待采样与SQL耗时共同支持热点库存更新的锁竞争判断。连接获取计时包含Hikari获取连接的完整过程，不将其命名为纯行锁等待。

## 连接池和行锁观测

| 并发 | Inventory连接数 | Inventory等待连接线程峰值 | Order等待连接线程峰值 | Inventory行锁等待关系峰值 |
| --- | --- | --- | --- | --- |
| 16 | 10 | 3 | 0 | 45 |
| 64 | 10 | 51 | 42 | 45 |
| 256 | 10 | 187 | 178 | 45 |

采样间隔250ms。行锁等待关系是`performance_schema.data_lock_waits`的关系行数：一个等待事务可对应多个阻塞事务，所以45条关系不代表45个独立等待线程。仅计本轮随机库存库与订单库的关系；全局InnoDB等待计数差值另存明细，可能包含其他数据库流量，不当作本服务专属指标。

256并发时Order连接池也有瞬时排队，但Order本地事务平均60ms，库存HTTP平均2,580ms。本轮应优先处理库存链路；扩大连接池可能增加对同一库存行的竞争，需通过对照实验判断。

第一轮未直接测量连接获取，其16/64/256并发吞吐分别59.0/60.9/62.9请求每秒，同样定位到库存侧等待。第一轮3,000次与第二轮3,000次下单均最终成功预留，库存与订单核对通过。第二轮全部HTTP200；无客户端异常或错误响应。日志与数据库采样本身有开销，本结果用于诊断，不作为新的生产容量承诺。

## 代码变化

1. `ticket-common/.../trace/PerformanceSpan.java`：可关闭的作用域及分段计时，每作用域一行，带traceId；不输出用户请求正文、Token或密码。
2. `OrderServiceImpl.create`：规则查询、幂等查询、本地事务、库存编排及最终详情查询。
3. `OrderTransactionService.create`：订单插入、订单项插入、额度初始化及占用SQL。
4. `OrderStockWorkflow`：领取任务、库存HTTP及保存预留结果。
5. `InternalStockReservationController.reserve`：在事务代理外计时，覆盖事务开始、获取连接和提交。
6. `InventoryServiceImpl.reserve`：预留插入、预留行锁查询、库存条件更新。
7. 两个服务的`PerformancePoolSampler`：连接池只读采样及Hikari原生连接获取/持有计时；已有指标插件时不覆盖它。

本次未调整库存SQL、事务规则、幂等逻辑、连接池大小或恢复参数。性能开关默认关闭。显式诊断时设置`TICKET_PERF_ENABLED=true`或JVM参数`-Dticket.perf.enabled=true`，并设置`--logging.level.ticket.performance=INFO`，然后重启应用。使用性能开关时需考虑日志与采样开销。

所有模块构建成功；关闭观测后的累计限购、重复下单与事务异常路径回归95项通过。

## 复现

```powershell
& ./deploy/verify/verify_profiling_load.ps1 -RequestsPerPhase 1000
& ./deploy/verify/summarize_profiling.ps1 -RunDirectory '.local/profiling-load/实际运行ID'
```

脚本使用隔离MySQL/Redis数据及Nacos分组，结束清理自己的业务进程和数据，保留日志。默认Order恢复参数仍为5秒扫描、每批20条；支付窗口设15分钟。观测脚本只跑16/64/256下单，不重复前一轮的支付出票场景。

原始数据：[HTTP结果](profiling-2026-10-05-summary.json)、[分段与池等待汇总](profiling-2026-10-05-details.json)、[分段CSV](profiling-2026-10-05-timings.csv)、[数据库等待CSV](profiling-2026-10-05-database.csv)、[请求CSV](profiling-2026-10-05-requests.csv)。原始日志位于`.local/profiling-load/53907c6f24ec495bab04c6f989381342`。

## 后续方向

下一步对库存连接池大小与不同票档分布做受控对照，确认热点锁串行化的影响；再检查事务提交与数据库IO耗时。Redis预扣、削峰队列或MQ等方案应以这组证据和对照结果决定。付款后的后台确认/出票速度仍需单独优化与验证。
