# 第一版功能验收记录

验收日期：2026-10-05（本地Windows、Java 17、MySQL、Redis、Nacos 3.1.2）。

## 结论

第一版后端功能验收通过。本次运行共312项检查：真实Nacos与网关验收80项、累计限购95项、支付履约及恢复137项。此结论针对以下功能与故障场景；性能测量另见文末报告，不将功能检查数量作为吞吐指标。

## 实际结果

| 验收范围 | 结果 |
| --- | --- |
| 六个服务注册 | Auth、Gateway、Event、Inventory、Order、Payment均在独立Nacos分组中查询到对应端口的健康实例 |
| 真实注册发现 | Gateway使用现有`lb://`路由，服务间通过Nacos发现实例；新增验收未使用活动接口桩或SimpleDiscoveryClient替代 |
| 网关完整业务链路 | 用户注册登录、管理员创建草稿、Event准备库存、发布、浏览、下单、模拟支付、确认售出、查询两张票券通过 |
| 下单幂等 | 相同购买键返回同一订单；订单表一条记录；库存只预留一次 |
| 权限 | 未登录、非管理员、非订单所有者、错误/交叉服务凭证、越权方法和未知内部路径按预期拒绝；拒绝写请求不改变库存 |
| 支付重复操作 | 重复模拟成功不多出票 |
| 实际超时 | 支付窗口设为30秒；未付款订单由后台关闭，预留量归零、可用量归还 |
| 库存停机恢复 | 停止测试Inventory后下单保留STOCK_PENDING；同端口重启后恢复为PENDING_PAYMENT；订单号不变且只预留一次 |
| 退出登录 | 退出后旧Access Token请求订单接口返回401 |
| 累计限购回归 | 同用户同场次跨票档累计限制、并发占用及额度归还等95项通过 |
| 支付恢复回归 | 正常成交、重复通知、确认响应丢失、临时不可用、保存结果失败、迟到支付冲正、关闭与确认竞争、重启及两个恢复实例等137项通过 |

后两组回归使用隔离数据及临时端口，部分依赖采用已有HTTP桩或故障注入；不将其计为Nacos验收。新增80项使用真实Nacos及完整业务服务。

## 数据与进程

每次创建随机测试数据库、Redis会话前缀、独立RSA密钥和服务凭证，使用临时业务端口及独立Nacos分组。完成后关闭自身业务进程，清理自身测试库和会话，保留忽略目录下的日志；现有业务数据库未写入验收订单。

本次启动了项目已有的本地Nacos，结束后仍保持运行：服务端口8848、客户端gRPC端口9848、控制台8080。验收业务服务已退出；继续开发时在IDEA启动自己的服务。

## 复现

启动MySQL、Redis、本地Nacos，确认Windows数据库环境变量和最新打包产物就绪，从项目根目录执行：

```powershell
& ./deploy/verify/verify_v1_acceptance.ps1
& ./deploy/verify/verify_purchase_quota.ps1
& ./deploy/verify/verify_payment_fulfillment.ps1
```

新增脚本检查Nacos的8848和9848端口。代码为`deploy/verify/V1AcceptanceVerification.java`，日志位于`.local/v1-acceptance/{随机ID}`，不输出Token、密码或私钥。

## 后续性能记录

功能验收之后已完成[性能基线](performance/baseline-2026-10-05.md)、[分段定位](performance/profiling-2026-10-05.md)及[热点对照](performance/comparison-2026-10-06.md)。第一版性能测量到此归档，优化安排在第二版；交付入口见[第一版交付说明](v1-delivery.md)。
