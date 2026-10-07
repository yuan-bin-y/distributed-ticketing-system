[CmdletBinding()]
param([string]$ProjectName='ticket-platform',[switch]$PersistenceOnly,[switch]$LibraryOnly)
. (Join-Path $PSScriptRoot 'common.ps1')
$script:TicketDocker=Get-TicketDocker
Initialize-TicketDockerEnvironment $ProjectName
$gatewayPort=if($env:TICKET_GATEWAY_PORT){$env:TICKET_GATEWAY_PORT}else{'18060'}
$base="http://localhost:$gatewayPort"
$reportPath=Join-Path $script:TicketProjectRoot '.local/docker/verification-result.json'
function Assert-Ticket($Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Api([string]$Method,[string]$Path,$Body=$null,[string]$Token='') {
    $arguments=@{Uri="$base$Path";Method=$Method;TimeoutSec=30}
    if($Token){$arguments.Headers=@{Authorization="Bearer $Token"}}
    if($null -ne $Body){$arguments.Body=$Body | ConvertTo-Json -Depth 15 -Compress; $arguments.ContentType='application/json; charset=utf-8'}
    # 错误时只报告接口及HTTP状态，不把登录凭证或请求正文输出到日志。
    try {$response=Invoke-RestMethod @arguments} catch {throw "接口 $Method $Path 失败，HTTP状态：$($_.Exception.Response.StatusCode)"}
    Assert-Ticket ($response.code -eq 'OK') "接口 $Path 返回业务错误：$($response.code)"
    return $response
}
function Sql([string]$Statement) {
    $output=& $script:TicketDocker compose -f (Join-Path $script:TicketProjectRoot 'compose.yml') exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "$1"' sh $Statement
    Assert-Ticket ($LASTEXITCODE -eq 0) '验证数据库查询失败。'
    return ($output -join "`n").Trim()
}
function Wait-Order([string]$Number,[string]$Status,[string]$Token) {
    $deadline=[DateTime]::UtcNow.AddSeconds(120)
    do {
        $order=(Api GET "/api/orders/$Number" $null $Token).data
        if($order.status -eq $Status){return $order}
        if($order.status -in @('CLOSED','REVIEW_REQUIRED')){throw "订单进入异常状态：$($order.status)"}
        Start-Sleep -Seconds 2
    } while([DateTime]::UtcNow -lt $deadline)
    throw "等待订单状态 $Status 超时，最后状态 $($order.status)"
}
if($LibraryOnly){return}
if($PersistenceOnly) {
    Assert-Ticket (Test-Path -LiteralPath $reportPath) '请先执行一次完整verify.ps1。'
    $saved=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    Assert-Ticket ($saved.orderNo -match '^[a-f0-9]{32}$') '验收记录订单编号不合法。'
    Assert-Ticket ((Sql "SELECT status FROM ticket_order.t_order WHERE order_no='$($saved.orderNo)'") -eq 'COMPLETED') '重启后订单数据不符合预期。'
    Assert-Ticket ((Api GET "/api/events/$($saved.eventId)").data.id -eq $saved.eventId) '重启后活动不可查询。'
    Write-Output '重启持久化验收通过：原订单仍COMPLETED，已发布活动仍可查询。'
    return
}
$nacosPort=if($env:TICKET_NACOS_PORT){$env:TICKET_NACOS_PORT}else{'18848'}
foreach($service in @('gateway','auth-service','event-service','order-service','inventory-service','payment-service')) {
    $instances=Invoke-RestMethod "http://localhost:$nacosPort/nacos/v1/ns/instance/list?serviceName=ticket-$service&groupName=TICKET_DOCKER&healthyOnly=true" -TimeoutSec 15
    Assert-Ticket (@($instances.hosts).Count -gt 0) "Nacos缺少健康实例 ticket-$service"
}
Write-Output 'Nacos：六个服务注册通过。'
$admin=(Api POST '/api/auth/login' @{username=$env:TICKET_DOCKER_ADMIN_USERNAME;password=$env:TICKET_DOCKER_ADMIN_PASSWORD}).data.accessToken
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
$buyer="docker_$suffix"
$password=[Guid]::NewGuid().ToString('N')+'Aa1!'
$null=Api POST '/api/auth/register' @{username=$buyer;password=$password;nickname='Docker验收用户'}
$token=(Api POST '/api/auth/login' @{username=$buyer;password=$password}).data.accessToken
$now=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'China Standard Time')
$format='yyyy-MM-ddTHH:mm:ss'
$draft=(Api POST '/api/admin/events' @{
    idempotencyKey="docker_event_$suffix";name="Docker验收活动-$suffix";category='CONCERT'
    sessions=@(@{name='演示场次';venueName='演示场馆';venueAddress='本地容器验收'
        startTime=$now.AddDays(1).ToString($format);endTime=$now.AddDays(1).AddHours(2).ToString($format)
        saleStartTime=$now.AddHours(-1).ToString($format);saleEndTime=$now.AddHours(12).ToString($format)
        purchaseLimit=2;ticketTiers=@(@{name='普通票';price=199.00;totalQuantity=10})})
} $admin).data
$eventId=$draft.eventId
$tierId=$draft.sessions[0].ticketTiers[0].ticketTierId
$deadline=[DateTime]::UtcNow.AddSeconds(120)
do {
    $draft=(Api GET "/api/admin/events/$eventId" $null $admin).data
    if($draft.sessions[0].ticketTiers[0].preparationStatus -eq 'READY'){break}
    Start-Sleep -Seconds 2
} while([DateTime]::UtcNow -lt $deadline)
Assert-Ticket ($draft.sessions[0].ticketTiers[0].preparationStatus -eq 'READY') '活动库存初始化超时。'
$null=Api POST "/api/admin/events/$eventId/publish" @{} $admin
$null=Api GET "/api/events/$eventId"
$request=@{ticketTierId=$tierId;quantity=2;idempotencyKey="docker_order_$suffix"}
$order=(Api POST '/api/orders' $request $token).data
$number=$order.orderNo
$repeated=(Api POST '/api/orders' $request $token).data
Assert-Ticket ($number -eq $repeated.orderNo) '重复下单没有返回原订单。'
$order=Wait-Order $number 'PENDING_PAYMENT' $token
Assert-Ticket ($order.totalAmount -eq 398) '订单金额不等于398。'
$payment=(Api POST "/api/orders/$number/payments" @{} $token).data
$success=Api POST "/api/payments/$($payment.paymentNo)/simulate-success" @{} $token
$order=Wait-Order $number 'COMPLETED' $token
$tickets=(Api GET "/api/orders/$number/tickets" $null $token).data.tickets
Assert-Ticket (@($tickets).Count -eq 2) '电子票数量不等于2。'
$null=Api POST "/api/payments/$($payment.paymentNo)/simulate-success" @{} $token
Assert-Ticket (@((Api GET "/api/orders/$number/tickets" $null $token).data.tickets).Count -eq 2) '重复支付生成了额外电子票。'
Assert-Ticket ((Sql "SELECT CONCAT(available_quantity,',',reserved_quantity,',',sold_quantity) FROM ticket_inventory.t_ticket_stock WHERE ticket_tier_id=$tierId") -eq '8,0,2') '库存数量不正确。'
Assert-Ticket ((Sql "SELECT COUNT(*) FROM ticket_order.t_order WHERE order_no='$number'") -eq '1') '订单没有保持幂等。'
Assert-Ticket ((Sql "SELECT COUNT(*) FROM ticket_payment.t_outbox_event WHERE aggregate_id='$($payment.paymentNo)' AND status='PUBLISHED'") -eq '1') 'Outbox没有成功发布。'
Assert-Ticket ((Sql "SELECT COUNT(*) FROM ticket_order.t_consumed_event WHERE order_no='$number'") -eq '1') '订单没有通过MQ消费付款事件。'
Write-Output '完整交易：登录、草稿准备/发布、幂等下单、MQ付款通知、出票、重复支付及库存核对通过。'
$tempoPort=if($env:TICKET_DOCKER_TEMPO_PORT){$env:TICKET_DOCKER_TEMPO_PORT}else{'3201'}
$deadline=[DateTime]::UtcNow.AddSeconds(60)
$traceFound=$false
do {
    try {
        $trace=Invoke-RestMethod "http://localhost:$tempoPort/api/traces/$($success.traceId)" -TimeoutSec 10
        $traceJson=$trace | ConvertTo-Json -Depth 100 -Compress
        $traceFound=$traceJson.Contains('payment.succeeded publish') -and $traceJson.Contains('payment.succeeded consume')
    } catch { $traceFound=$false }
    if(-not $traceFound){Start-Sleep -Seconds 2}
} while(-not $traceFound -and [DateTime]::UtcNow -lt $deadline)
Assert-Ticket $traceFound 'Tempo中未找到同一traceId下的MQ发送和消费Span。'
$grafanaPort=if($env:TICKET_DOCKER_GRAFANA_PORT){$env:TICKET_DOCKER_GRAFANA_PORT}else{'3002'}
Assert-Ticket ((Invoke-RestMethod "http://localhost:$grafanaPort/api/health" -TimeoutSec 15).database -eq 'ok') 'Grafana健康检查失败。'
$report=@{verifiedAt=[DateTime]::UtcNow.ToString('o');project=$ProjectName;eventId=$eventId;ticketTierId=$tierId;orderNo=$number;paymentNo=$payment.paymentNo;traceId=$success.traceId;ticketCount=2;inventory='8,0,2';mq='PUBLISHED/CONSUMED';tempo='MQ spans verified'}
[IO.File]::WriteAllText($reportPath,($report | ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
Write-Output "Tempo与Grafana通过。验收结果：$reportPath（不含密码和Token）。"
