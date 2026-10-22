# 交易可靠性与部署运维

## Outbox 人工核对与重放

自动发布默认每轮最多尝试 20 次或持续 24 小时。损坏载荷、Broker 认证失败立即进入
`REVIEW_REQUIRED`，临时连接、路由或 Confirm 故障在预算内指数退避。达到阈值只暂停自动发布，
原事件和已成功付款的事实始终保留。`TicketManualReviewRequired` 对待核对事件告警。

先修复故障并核对付款、订单、库存事实，再重放。设置 `JAVA_HOME`、`PAYMENT_DB_URL`、
`LOCAL_MYSQL_USERNAME`、`LOCAL_MYSQL_PASSWORD` 环境变量，使用可访问 Payment 库的运维账号。
密码从现有 Windows 环境或受控终端注入，不写进命令参数或仓库。

```powershell
# 默认只列出待核对事件，不改变数据库
./deploy/mq/review_outbox.ps1
# 显式重放原编号，必须填写操作人和原因
./deploy/mq/review_outbox.ps1 -EventId <原eventId> -Operator <操作人> -Reason '已恢复Broker路由并核对付款事实'
```

重放会锁定待核对记录，校验原支付成功事实与消息归属，在同一事务内写入
`t_outbox_redrive` 审计记录和恢复待发布状态。保留 `event_id`、原载荷、付款编号和总尝试次数，
只重置本轮自动预算。重复操作和活跃租约记录不能被重放。确认已发布不等于订单已出票，
最终仍需检查订单、消费记录及电子票。

相关参数：`ticket.mq.outbox-max-attempts`、`ticket.mq.outbox-max-age`。
