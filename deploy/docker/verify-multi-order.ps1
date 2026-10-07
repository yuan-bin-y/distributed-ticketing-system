[CmdletBinding()]
param([string]$ProjectName='ticket-platform')
# 复用真实API与数据库核对，不打印凭证；本脚本要求PowerShell7。
. (Join-Path $PSScriptRoot 'verify.ps1') -ProjectName $ProjectName -LibraryOnly
Assert-Ticket ($PSVersionTable.PSVersion.Major -ge 7) '请使用PowerShell7运行多实例验收。'
$nacosPort=if($env:TICKET_NACOS_PORT){$env:TICKET_NACOS_PORT}else{'18848'}
$tempoPort=if($env:TICKET_DOCKER_TEMPO_PORT){$env:TICKET_DOCKER_TEMPO_PORT}else{'3201'}
function Instances { (Invoke-RestMethod "http://localhost:$nacosPort/nacos/v1/ns/instance/list?serviceName=ticket-order-service&groupName=TICKET_DOCKER&healthyOnly=true" -TimeoutSec 15).hosts }
function Container-Address([string]$Name) {
    $details=(& $script:TicketDocker inspect $Name | ConvertFrom-Json)[0]
    return ($details.NetworkSettings.Networks.PSObject.Properties.Value | Select-Object -First 1).IPAddress
}
function Wait-Instances([int]$Count) {
    $deadline=[DateTime]::UtcNow.AddSeconds(120)
    do {if(@(Instances).Count -eq $Count){return}; Start-Sleep -Seconds 2} while([DateTime]::UtcNow -lt $deadline)
    throw "Nacos未收敛到$Count个Order实例。"
}
function New-Buyer([string]$Suffix) {
    $user="multi_$Suffix"; $password=[Guid]::NewGuid().ToString('N')+'Aa1!'
    $null=Api POST '/api/auth/register' @{username=$user;password=$password;nickname='多实例验收'}
    return (Api POST '/api/auth/login' @{username=$user;password=$password}).data.accessToken
}
function Wait-FailoverOrder([string]$Number,[string]$Token) {
    $deadline=[DateTime]::UtcNow.AddSeconds(120); $last=''
    do {
        # Nacos摘除与网关本地实例缓存存在收敛时间，记录短暂失败而不是假设立即切换。
        try {
            $order=(Api GET "/api/orders/$Number" $null $Token).data
            if($order.status -eq 'PENDING_PAYMENT'){return $order}
            $last=$order.status
        } catch {$last=$_.Exception.Message; $script:failoverTransientErrors++}
        Start-Sleep -Seconds 2
    } while([DateTime]::UtcNow -lt $deadline)
    throw "故障后网关/订单恢复超时：$last"
}
function Trace-Instances([string[]]$TraceIds) {
    $found=@()
    foreach($traceId in $TraceIds) {
        try {$trace=Invoke-RestMethod "http://localhost:$tempoPort/api/traces/$traceId" -TimeoutSec 10} catch {continue}
        foreach($batch in @($trace.batches)+@($trace.resourceSpans)) {
            $attributes=$batch.resource.attributes
            if(($attributes | Where-Object key -eq 'service.name').value.stringValue -eq 'ticket-order-service') {
                $id=($attributes | Where-Object key -eq 'service.instance.id').value.stringValue
                if($id){$found += $id}
            }
        }
    }
    return @($found | Sort-Object -Unique)
}
. (Join-Path $PSScriptRoot 'verify-order-graceful.ps1')
Invoke-TicketCompose up -d --no-deps --scale order=2 --wait --wait-timeout 180 order
Wait-Instances 2
$containers=@(& $script:TicketDocker ps --filter "label=com.docker.compose.project=$ProjectName" --filter 'label=com.docker.compose.service=order' --format '{{.Names}}' | Sort-Object)
Assert-Ticket ($containers.Count -eq 2) '应有两个运行中的Order容器。'
$ids=@($containers | ForEach-Object {(& $script:TicketDocker inspect --format '{{.Config.Hostname}}' $_).Trim()})
Write-Output '两个Order容器健康，Nacos注册2个实例。'
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,10)
$admin=(Api POST '/api/auth/login' @{username=$env:TICKET_DOCKER_ADMIN_USERNAME;password=$env:TICKET_DOCKER_ADMIN_PASSWORD}).data.accessToken
$token=New-Buyer $suffix
$now=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'China Standard Time'); $format='yyyy-MM-ddTHH:mm:ss'
$draft=(Api POST '/api/admin/events' @{
    idempotencyKey="multi_event_$suffix";name="多实例验收-$suffix";category='CONCERT'
    sessions=@(@{name='双实例场次';venueName='测试场馆';venueAddress='本地Docker'
        startTime=$now.AddDays(1).ToString($format);endTime=$now.AddDays(1).AddHours(2).ToString($format)
        saleStartTime=$now.AddHours(-1).ToString($format);saleEndTime=$now.AddHours(12).ToString($format)
        purchaseLimit=2;ticketTiers=@(@{name='普通票';price=199;totalQuantity=10})})
} $admin).data
$eventId=$draft.eventId; $tierId=$draft.sessions[0].ticketTiers[0].ticketTierId
$deadline=[DateTime]::UtcNow.AddSeconds(120)
do {
    $draft=(Api GET "/api/admin/events/$eventId" $null $admin).data
    if($draft.sessions[0].ticketTiers[0].preparationStatus -eq 'READY'){break}; Start-Sleep -Seconds 2
} while([DateTime]::UtcNow -lt $deadline)
Assert-Ticket ($draft.sessions[0].ticketTiers[0].preparationStatus -eq 'READY') '库存准备超时。'
$null=Api POST "/api/admin/events/$eventId/publish" @{} $admin
# 8个请求同时发到网关：保持在默认单用户限流突发额度以内。
$client=[Net.Http.HttpClient]::new(); $client.Timeout=[TimeSpan]::FromSeconds(40)
$tasks=@(); $messages=@(); $responses=@()
try {
    $json=@{ticketTierId=$tierId;quantity=2;idempotencyKey="multi_order_$suffix"} | ConvertTo-Json -Compress
    for($i=0;$i -lt 8;$i++) {
        $message=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post,"$base/api/orders")
        $message.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
        $message.Content=[Net.Http.StringContent]::new($json,[Text.Encoding]::UTF8,'application/json')
        $messages += $message; $tasks += $client.SendAsync($message)
    }
    foreach($task in $tasks) {
        $response=$task.GetAwaiter().GetResult()
        try {
            Assert-Ticket $response.IsSuccessStatusCode "并发下单HTTP失败：$([int]$response.StatusCode)"
            $body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            Assert-Ticket ($body.code -eq 'OK') "并发下单业务失败：$($body.code)"
            $responses += $body
        } finally {$response.Dispose()}
    }
} finally {foreach($message in $messages){$message.Dispose()};$client.Dispose()}
$numbers=@($responses.data.orderNo | Sort-Object -Unique)
Assert-Ticket ($numbers.Count -eq 1) '并发同幂等键产生了多个订单。'
$number=$numbers[0]; $null=Wait-Order $number 'PENDING_PAYMENT' $token
Assert-Ticket ((Sql "SELECT COUNT(*) FROM ticket_order.t_order WHERE order_no='$number'") -eq '1') '订单记录不唯一。'
Assert-Ticket ((Sql "SELECT COUNT(*) FROM ticket_inventory.t_stock_reservation WHERE order_id='$number'") -eq '1') '库存预留记录不唯一。'
$deadline=[DateTime]::UtcNow.AddSeconds(60)
do {$served=@(Trace-Instances $responses.traceId); if($served.Count -ge 2){break}; Start-Sleep -Seconds 2} while([DateTime]::UtcNow -lt $deadline)
Assert-Ticket ($served.Count -eq 2 -and @($served | Where-Object {$_ -notin $ids}).Count -eq 0) '链路记录未证明两个Order实例均处理网关请求。'
Write-Output '8个网关并发请求由两个实例处理：订单1条，库存预留1条。'
$payment=(Api POST "/api/orders/$number/payments" @{} $token).data
$null=Api POST "/api/payments/$($payment.paymentNo)/simulate-success" @{} $token
$null=Wait-Order $number 'COMPLETED' $token
Assert-Ticket (@((Api GET "/api/orders/$number/tickets" $null $token).data.tickets).Count -eq 2) '双实例出票数量错误。'
Assert-Ticket ((Sql "SELECT COUNT(*) FROM ticket_order.t_consumed_event WHERE order_no='$number'") -eq '1') '双实例MQ消费记录不唯一。'
$gracefulResult=Test-OrderGraceful $containers[0] $number $token
$readRetryResult=Test-OrderReadRetry $containers[0] $number $token
# 故障注入：暂停库存让A停在已领取订单的远程调用中，再强制停止A。
# B保持运行；不直接修改订单状态/租约字段，让它等待真实租约到期并接管。
$faultToken=New-Buyer ($suffix+'f'); $faultKey="multi_fault_$suffix"
$inventory=@(& $script:TicketDocker ps --filter "label=com.docker.compose.project=$ProjectName" --filter 'label=com.docker.compose.service=inventory' --format '{{.Names}}')[0]
$gateway=@(& $script:TicketDocker ps --filter "label=com.docker.compose.project=$ProjectName" --filter 'label=com.docker.compose.service=gateway' --format '{{.Names}}')[0]
$target=$containers[0]; $survivor=$containers[1]; $address=Container-Address $target
$paused=$false; $survivorPaused=$false; $job=$null; $script:failoverTransientErrors=0
try {
    # 短暂冻结B以确定该租约由A领取；A停止后立即恢复B。
    & $script:TicketDocker pause $survivor | Out-Null
    Assert-Ticket ($LASTEXITCODE -eq 0) '无法暂时冻结B。'; $survivorPaused=$true
    & $script:TicketDocker pause $inventory | Out-Null
    Assert-Ticket ($LASTEXITCODE -eq 0) '无法暂停库存。'; $paused=$true
    $body=@{ticketTierId=$tierId;quantity=1;idempotencyKey=$faultKey} | ConvertTo-Json -Compress
    $job=Start-ThreadJob -ArgumentList $script:TicketDocker,$gateway,$address,$faultToken,$body -ScriptBlock {
        param($docker,$container,$address,$token,$json)
        $json | & $docker exec -i $container curl -sS --max-time 40 -X POST "http://${address}:8062/api/orders" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary '@-'
    }
    $deadline=[DateTime]::UtcNow.AddSeconds(20); $claimed=''
    do {
        $claimed=Sql "SELECT order_no FROM ticket_order.t_order WHERE idempotency_key='$faultKey' AND status='STOCK_PENDING' AND lease_token IS NOT NULL AND lease_until>CURRENT_TIMESTAMP(3)"
        if($claimed){break}; Start-Sleep -Milliseconds 100
    } while([DateTime]::UtcNow -lt $deadline)
    Assert-Ticket ($claimed -match '^[a-f0-9]{32}$') '没有观察到持有有效租约的STOCK_PENDING订单。'
    & $script:TicketDocker stop --time 0 $target | Out-Null
    Assert-Ticket ($LASTEXITCODE -eq 0) '停止Order A失败。'
    $faultNumber=$claimed
    & $script:TicketDocker unpause $survivor | Out-Null; Assert-Ticket ($LASTEXITCODE -eq 0) '恢复B失败。'; $survivorPaused=$false
    & $script:TicketDocker unpause $inventory | Out-Null; Assert-Ticket ($LASTEXITCODE -eq 0) '恢复库存失败。'; $paused=$false
    # 在等待Nacos收敛之前立即查询，故意覆盖网关仍可能选中故障地址的时间窗。
    $probeSince=[DateTime]::UtcNow.ToString('o'); $probeFailures=0; $maxReadMs=0
    for($probe=0;$probe -lt 6;$probe++) {
        $timer=[Diagnostics.Stopwatch]::StartNew()
        try {$null=Api GET "/api/orders/$number" $null $token} catch {$probeFailures++}
        $timer.Stop(); $maxReadMs=[Math]::Max($maxReadMs,$timer.Elapsed.TotalMilliseconds)
    }
    $retryLogs=(& $script:TicketDocker logs --since $probeSince $gateway 2>&1) -join "`n"
    $switches=[regex]::Matches($retryLogs,'order.read.retry from=').Count
    Assert-Ticket ($probeFailures -eq 0) "强制停机后6次立即查询失败${probeFailures}次。"
    Write-Output "强制停机后立即查询6次：失败0次，实际换实例重试${switches}次。"
    Wait-Instances 1
    $null=Wait-FailoverOrder $faultNumber $faultToken
    $faultPayment=(Api POST "/api/orders/$faultNumber/payments" @{} $faultToken).data
    $faultSuccess=Api POST "/api/payments/$($faultPayment.paymentNo)/simulate-success" @{} $faultToken
    $null=Wait-Order $faultNumber 'COMPLETED' $faultToken
    Assert-Ticket (@((Api GET "/api/orders/$faultNumber/tickets" $null $faultToken).data.tickets).Count -eq 1) '接管后出票数量错误。'
    Assert-Ticket ((Sql "SELECT COUNT(*) FROM ticket_inventory.t_stock_reservation WHERE order_id='$faultNumber'") -eq '1') '接管时产生了重复预留。'
    Assert-Ticket ((Sql "SELECT COUNT(*) FROM ticket_order.t_consumed_event WHERE order_no='$faultNumber'") -eq '1') '接管后没有唯一MQ消费记录。'
    Assert-Ticket ((Sql "SELECT COUNT(*) FROM ticket_payment.t_outbox_event WHERE aggregate_id='$($faultPayment.paymentNo)' AND status='PUBLISHED'") -eq '1') '接管后的付款消息没有成功发布。'
    Assert-Ticket ((Sql "SELECT CONCAT(available_quantity,',',reserved_quantity,',',sold_quantity) FROM ticket_inventory.t_ticket_stock WHERE ticket_tier_id=$tierId") -eq '7,0,3') '两个案例的最终库存不正确。'
    Write-Output '强制停止A后，B等待租约到期接管未完成订单，并经网关完成支付、MQ消费与出票；最终库存7/0/3。'
} finally {
    if($paused){& $script:TicketDocker unpause $inventory | Out-Null}
    if($survivorPaused){& $script:TicketDocker unpause $survivor | Out-Null}
    if($job){$null=Wait-Job $job -Timeout 45; Remove-Job $job -Force -ErrorAction SilentlyContinue}
    Invoke-TicketCompose up -d --no-deps --scale order=2 --wait --wait-timeout 180 order
    Wait-Instances 2
}
$report=@{verifiedAt=[DateTime]::UtcNow.ToString('o');project=$ProjectName;eventId=$eventId;ticketTierId=$tierId;concurrentRequests=8;servingInstances=$served;orderNo=$number;faultOrderNo=$faultNumber;stoppedContainer=$target;graceful=$gracefulResult;frozenInstanceReadProbe=$readRetryResult;immediateGetProbe=@{requests=6;failures=$probeFailures;retrySwitches=$switches;maxElapsedMs=[Math]::Round($maxReadMs,2)};failoverTransientErrors=$script:failoverTransientErrors;finalInventory='7,0,3';restoredOrderInstances=2;result='PASS'}
$path=Join-Path $script:TicketProjectRoot '.local/docker/multi-order-result.json'
[IO.File]::WriteAllText($path,($report | ConvertTo-Json -Depth 8),(New-Object Text.UTF8Encoding($false)))
Write-Output "多实例验收全部通过；两个Order实例已恢复。结果文件：$path"
