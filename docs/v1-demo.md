# 第一版 API 演示步骤

先完成 [启动指南](v1-startup.md)，六个服务已启动，管理员已初始化，Payment 已开启模拟。以下 PowerShell 代码按顺序在同一窗口执行，全部业务请求经过 Gateway 8060。需要网络和本地数据库，但不用手工插入演示库存。

本演示会在正常业务库创建账号、活动、订单、支付与票券，数据保留供查看。它与自动清理随机测试库的验收脚本用途不同。

## 请求工具与登录

```powershell
$apiBase = 'http://localhost:8060'
function Invoke-TicketApi {
    param([string]$Method, [string]$Path, [string]$Token = '', $Body = $null)
    $apiHeaders = @{}
    if ($Token) { $apiHeaders.Authorization = "Bearer $Token" }
    $apiArgs = @{Method=$Method;Uri="$apiBase$Path";Headers=$apiHeaders}
    if ($null -ne $Body) {
        $apiArgs.ContentType = 'application/json; charset=utf-8'
        $apiArgs.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 12))
    }
    $apiResult = Invoke-RestMethod @apiArgs
    if ($apiResult.code -ne 'OK') { throw "请求失败：$($apiResult.code) $($apiResult.message)" }
    return $apiResult.data
}

# 密码交互输入，Token 仅保存在本窗口变量，不打印完整登录响应。
$adminName = Read-Host '已初始化的管理员账号'
$adminCredential = Get-Credential -UserName $adminName -Message '管理员密码'
$adminLogin = Invoke-TicketApi -Method POST -Path '/api/auth/login' -Body @{
    username=$adminName; password=$adminCredential.GetNetworkCredential().Password
}
$adminToken = $adminLogin.accessToken

# 每次演示生成新账号，避免重复注册和历史限购影响。
$demoTag = [Guid]::NewGuid().ToString('N').Substring(0,12)
$buyerName = "demo_$demoTag"
$buyerCredential = Get-Credential -UserName $buyerName -Message '设置演示用户密码，8至72字符且UTF-8不超过72字节'
$null = Invoke-TicketApi -Method POST -Path '/api/auth/register' -Body @{
    username=$buyerName; password=$buyerCredential.GetNetworkCredential().Password; nickname='演示购票用户'
}
$buyerLogin = Invoke-TicketApi -Method POST -Path '/api/auth/login' -Body @{
    username=$buyerName; password=$buyerCredential.GetNetworkCredential().Password
}
$buyerToken = $buyerLogin.accessToken
```

预期管理员登录成功，注册用户固定 USER 角色。无需在业务正文里提供用户 ID。Access Token 默认 15 分钟，演示过久需重新登录。

## 管理员创建草稿与发布

时间按东八区本地时间传入，以下动态生成“已经开售、明天演出”的示例。固定 `$draftBody` 后重复提交需保持原内容与幂等键。

```powershell
$demoNow = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(8)).DateTime
$draftBody = @{
    idempotencyKey="event_$demoTag"; name='第一版演示活动'; category='CONCERT'
    sessions=@(@{
        name='演示场次'; venueName='演示体育馆'; venueAddress='本地演示地址'
        startTime=$demoNow.AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss')
        endTime=$demoNow.AddDays(1).AddHours(2).ToString('yyyy-MM-ddTHH:mm:ss')
        saleStartTime=$demoNow.AddHours(-1).ToString('yyyy-MM-ddTHH:mm:ss')
        saleEndTime=$demoNow.AddHours(12).ToString('yyyy-MM-ddTHH:mm:ss')
        purchaseLimit=2
        ticketTiers=@(@{name='普通票';price=199.00;totalQuantity=10})
    })
}
$draft = Invoke-TicketApi -Method POST -Path '/api/admin/events' -Token $adminToken -Body $draftBody
$eventId = $draft.eventId

# Event 后台准备库存。最多等待90秒；超出后保留草稿检查原因。
$prepareDeadline = [DateTime]::UtcNow.AddSeconds(90)
do {
    $draft = Invoke-TicketApi -Method GET -Path "/api/admin/events/$eventId" -Token $adminToken
    $tierStates = @($draft.sessions | ForEach-Object { $_.ticketTiers } | ForEach-Object { $_.preparationStatus })
    if ($tierStates -contains 'REVIEW_REQUIRED') { throw '库存准备需要核对，请查看管理查询的 lastError' }
    if (@($tierStates | Where-Object { $_ -ne 'READY' }).Count -eq 0) { break }
    Start-Sleep -Seconds 2
} while ([DateTime]::UtcNow -lt $prepareDeadline)
if (@($tierStates | Where-Object { $_ -ne 'READY' }).Count -gt 0) { throw '库存尚未准备完成，请检查 Event/Inventory 日志' }

$published = Invoke-TicketApi -Method POST -Path "/api/admin/events/$eventId/publish" -Token $adminToken
$ticketTierId = $published.sessions[0].ticketTiers[0].ticketTierId
$published | Select-Object eventId,name,status
$null = Invoke-TicketApi -Method GET -Path "/api/events/$eventId"
$null = Invoke-TicketApi -Method GET -Path "/api/events/$eventId/sessions"
```

预期票档 READY、活动 PUBLISHED，公共查询可见。草稿初次落库不等于可售；后台确认库存后发布才开放购买。

## 下单与重复提交

```powershell
$orderBody = @{ticketTierId=$ticketTierId;quantity=2;idempotencyKey="buy_$demoTag"}
$order = Invoke-TicketApi -Method POST -Path '/api/orders' -Token $buyerToken -Body $orderBody
$orderNo = $order.orderNo
$repeatOrder = Invoke-TicketApi -Method POST -Path '/api/orders' -Token $buyerToken -Body $orderBody
if ($repeatOrder.orderNo -ne $orderNo) { throw '重复提交未返回原订单' }

$stockDeadline = [DateTime]::UtcNow.AddSeconds(90)
while ($order.status -eq 'STOCK_PENDING' -and [DateTime]::UtcNow -lt $stockDeadline) {
    Start-Sleep -Seconds 2
    $order = Invoke-TicketApi -Method GET -Path "/api/orders/$orderNo" -Token $buyerToken
}
if ($order.status -ne 'PENDING_PAYMENT') { throw "订单尚不可付款：$($order.status)" }
$order | Select-Object orderNo,status,quantity,totalAmount,expiresAt
```

预期同一幂等键得到同一订单、数量 2、金额 398.00，库存只预留一次。202 表示仍在推进，可按 orderNo 查询；不能换键再抢。此用户已占满该场次两张额度，换新键继续购买会触发限购。

## 模拟支付与出票

```powershell
$payment = Invoke-TicketApi -Method POST -Path "/api/orders/$orderNo/payments" -Token $buyerToken
$paymentNo = $payment.paymentNo
$paid = Invoke-TicketApi -Method POST -Path "/api/payments/$paymentNo/simulate-success" -Token $buyerToken
$paid | Select-Object paymentNo,status,paidAt

$fulfillmentDeadline = [DateTime]::UtcNow.AddSeconds(90)
do {
    $order = Invoke-TicketApi -Method GET -Path "/api/orders/$orderNo" -Token $buyerToken
    if ($order.status -eq 'COMPLETED') { break }
    if ($order.status -in @('CREATE_FAILED','CLOSED','REVERSED','REVIEW_REQUIRED')) {
        throw "未完成出票，请核对状态：$($order.status)"
    }
    Start-Sleep -Seconds 2
} while ([DateTime]::UtcNow -lt $fulfillmentDeadline)
if ($order.status -ne 'COMPLETED') { throw '后台仍未完成履约，请保存订单号检查日志' }

$tickets = Invoke-TicketApi -Method GET -Path "/api/orders/$orderNo/tickets" -Token $buyerToken
$tickets | ConvertTo-Json -Depth 6
```

预期订单 COMPLETED，返回两张独立票号。模拟付款成功后还要经过通知、回查、确认售出与出票；重复付款不重复出票。

## 退出与权限演示

```powershell
$null = Invoke-TicketApi -Method POST -Path '/api/auth/logout' -Token $buyerToken
# 此处预期HTTP401，Invoke-RestMethod抛出错误；表示原会话已失效。
Invoke-TicketApi -Method GET -Path "/api/orders/$orderNo" -Token $buyerToken
```

可另外使用普通用户 Token 请求管理接口，应返回 403；其他用户查询这笔订单不会取得其内容。请勿在截图或日志中展示完整 Token、私钥或服务凭证。

## 未支付到期与数据核对

到期演示另开新用户、使用新购买键下单，保持不付款。默认支付窗口 15 分钟；如需快速演示，可在 Order 运行配置设置 `ORDER_PAYMENT_WINDOW=30s` 后重启，创建新订单，观察 `PENDING_PAYMENT → CLOSING → CLOSED`。演示完成移除该覆盖并重启；不要用 30 秒窗口进行上面的手工完整支付演示。

只读核对可在 IDEA 数据库控制台执行，编号替换成当前演示值：

```sql
-- ticket_order
SELECT order_no, status, total_amount FROM t_order WHERE order_no = '演示订单号';
SELECT COUNT(*) FROM t_ticket t
JOIN t_order_item i ON i.id = t.order_item_id
JOIN t_order o ON o.id = i.order_id
WHERE o.order_no = '演示订单号';

-- ticket_inventory
SELECT total_quantity, available_quantity, reserved_quantity, sold_quantity
FROM t_ticket_stock WHERE ticket_tier_id = 这里填票档数字ID;
```

仅完成上述一笔两张付款订单时，该票档总量 10、可用 8、预留 0、售出 2，电子票两条；另有未付订单时预留量随其状态变化。不要直接修改业务状态来伪造成功。

服务停机、响应丢失、迟到付款等故障演示已有隔离验证，复现入口见 [验收记录](v1-acceptance.md)。这些脚本结束会清理自身数据，正常库的手工演示记录保留。
