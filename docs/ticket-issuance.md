# 电子票生成与本人查询

## 完成的功能

订单确认有效付款及原库存售出后先进入 PAID。现有恢复任务再领取 PAID，在订单库本地事务中生成每张电子票，并把订单改为 COMPLETED。一张订单项购买N张票，就对应N条票记录。

PAID 表示交易成交但出票尚未完成；COMPLETED 表示电子票和完成状态已经一起提交。网络服务调用沿用前面的成交流程，出票本身不新增跨服务 HTTP 或 MQ。

当前只生成独立票号和查询电子票，不包含二维码、入场核验、主动退款或票作废流程。独立手工模拟冲正不是完整退款功能；已出票订单出现外部冲正时仍需人工核对，不自动撤销已售出的库存或生成新的票。

## 数据库

订单服务新增 Flyway V4，不改已有 V1/V2/V3。订单库增加 t_ticket：

| 字段 | 含义 |
| --- | --- |
| id | 本库主键 |
| ticket_no | 每张票的唯一32位票号 |
| order_item_id | 订单项，本地外键 |
| ticket_index | 该订单项第几张票，从1开始 |
| status | 本阶段新票均为VALID |
| created_at | 出票时间 |

ticket_no唯一，(order_item_id,ticket_index)唯一。用户归属由订单表校验，活动、场次和票档信息从订单项购买快照读取，不再跨库读取活动表或重复保存用户编号。

V4允许COMPLETED并要求其有付款与预留依据；旧PAID记录设为立即可恢复。原订单库2张业务表变为3张，另有Flyway历史表。

## 按顺序读代码

1. `OrderMapper.selectDue()/claim()`：扫描范围增加PAID，仍用原租约和令牌控制多实例领取。
2. `OrderStockWorkflow.advance()`：PAID分支调用独立Bean的 `OrderTicketIssueTransaction.issue(id,token)`。
3. `issue()`：本地事务锁定订单行，检查仍为PAID且领取令牌匹配；旧任务或已完成订单直接返回。
4. `OrderItemMapper.selectByOrderId()`：读取已经保存的购买数量。
5. `ElectronicTicketMapper.selectByItemForUpdate()`：核对已有票序号范围和状态，保留已保存票号，只补缺少的序号。
6. 每100条分批执行 `insertTickets()`，各批仍属于同一个事务，不分开提交。
7. `OrderMapper.finishIssuing()`：全部插入后，按PAID与原令牌条件更新COMPLETED；更新失败抛异常，使此前所有插票回滚。
8. 事务提交后，电子票和完成状态同时可见。失败时订单保持PAID，外层保存退避时间，后台再次恢复。

```text
有效付款 + SOLD库存
    → PAID
    → 领取任务
    → 本地事务：锁定订单 → 保存全部电子票 → COMPLETED
    → 用户查询票列表
```

重复任务由订单行锁、状态条件、领取令牌和数据库唯一键共同保护。进程在事务提交前退出，数据库回滚；已经提交则订单为COMPLETED，后续任务不重复发票。

## 查询接口

```http
GET /api/orders/{orderNo}/tickets
```

沿用现有网关 `/api/orders/**` 路由和订单身份规则；没有新的运行服务或网关路由。默认需要可信身份，本地学习显式开启ORDER_DEV_IDENTITY_ENABLED=true后可传X-Dev-User-Id。

Controller → OrderTicketService → OrderTicketServiceImpl → Mapper。先查当前用户拥有的订单，再读取该订单项与电子票。查询使用可重复读本地事务，使订单状态与票列表属于同一数据库快照；正常出票过程中可见PAID+空列表或COMPLETED+全部票，不会返回半套新票。

响应示例：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "orderNo": "原订单编号",
    "orderStatus": "COMPLETED",
    "tickets": [
      {
        "ticketNo": "该张票的32位票号",
        "ticketIndex": 1,
        "status": "VALID",
        "eventId": 1,
        "sessionId": 1,
        "ticketTierId": 1,
        "ticketTierName": "下单时的名称",
        "createdAt": "2026-10-04T20:00:00"
      }
    ]
  },
  "traceId": "请求追踪编号"
}
```

未付款或等待出票的本人订单返回200、订单状态和空票列表。无身份401；他人订单或不存在订单404；错误编号400；接口只接受GET，不暴露公共手动出票入口。已冲正订单不会进入出票步骤。

## 启动与验证

重新加载Maven，重启订单服务即可自动执行V4；已有PAID订单由后台出票。使用此前创建订单→创建支付单→模拟付款流程，随后查询订单与电子票，后台默认每批间隔5秒。

`deploy/verify/verify_ticket_issuance.ps1`使用真实MySQL和两个独立Spring上下文，只创建和删除随机订单测试库，不写默认业务库。已通过65项检查，包括V3→V4升级、已有成交订单自动出票、本人查询、归属隔离、两实例20次并发推进、唯一约束、旧领取令牌、非PAID状态不出票、跨100条批次失败全回滚、完成状态更新失败全回滚、出票期间查询的一致快照、后台恢复和重启票号保持。

单独出票验证使用已成交的订单数据库夹具，不连接库存或支付服务；三服务交易链路另由 `verify_payment_fulfillment.ps1`通过135项检查，验证正常成交后的出票与冲正订单无票，并回归关闭竞争、响应丢失和恢复。本次没有重新验证真实Nacos注册或网关。
