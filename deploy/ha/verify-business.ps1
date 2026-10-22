param([ValidateSet(18070,18071)][int]$Port=18070)
. (Join-Path $PSScriptRoot 'common.ps1')
$base="http://127.0.0.1:$Port"
function Assert-Ha($Condition,[string]$Message){if(-not $Condition){throw $Message}}
function Api-Ha([string]$Method,[string]$Path,$Body=$null,[string]$Token='') {
    $options=@{Uri="$base$Path";Method=$Method;TimeoutSec=30}
    if($Token){$options.Headers=@{Authorization="Bearer $Token"}}
    if($null -ne $Body){$options.Body=ConvertTo-Json -InputObject $Body -Depth 15 -Compress;$options.ContentType='application/json; charset=utf-8'}
    try {$response=Invoke-RestMethod @options}
    catch {throw "HA业务接口失败：$Method $Path；HTTP=$($_.Exception.Response.StatusCode)"}
    Assert-Ha ($response.code -eq 'OK') "HA业务返回错误：$Path / $($response.code)"
    return $response.data
}
function Wait-HaOrder([string]$Number,[string]$Status,[string]$Token) {
    $deadline=[DateTime]::UtcNow.AddSeconds(120)
    do {
        $order=Api-Ha GET "/api/orders/$Number" $null $Token
        if($order.status -eq $Status){return $order}
        if($order.status -in @('CLOSED','REVIEW_REQUIRED')){throw "订单进入异常状态：$($order.status)"}
        Start-Sleep -Seconds 2
    } while([DateTime]::UtcNow -lt $deadline)
    throw "等待HA订单$Status超时"
}

# 每次生成新的业务标识，保留真实交易结果，不删除或覆盖已有业务数据。
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
$adminPassword=[IO.File]::ReadAllText((Join-Path $script:HaPrivate 'auth-admin-password')).Trim()
$admin=(Api-Ha POST '/api/auth/login' @{username='ha_admin';password=$adminPassword}).accessToken
$buyer="ha_$suffix";$password=[Guid]::NewGuid().ToString('N')+'Aa1!'
$null=Api-Ha POST '/api/auth/register' @{username=$buyer;password=$password;nickname='HA交易探针'}
$token=(Api-Ha POST '/api/auth/login' @{username=$buyer;password=$password}).accessToken
$now=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'China Standard Time')
$format='yyyy-MM-ddTHH:mm:ss'
$draft=Api-Ha POST '/api/admin/events' @{
    idempotencyKey="ha_event_$suffix";name="HA交易-$suffix";category='CONCERT'
    sessions=@(@{name='HA场次';venueName='HA场馆';venueAddress='本地隔离演练'
        startTime=$now.AddDays(1).ToString($format);endTime=$now.AddDays(1).AddHours(2).ToString($format)
        saleStartTime=$now.AddHours(-1).ToString($format);saleEndTime=$now.AddHours(12).ToString($format)
        purchaseLimit=2;ticketTiers=@(@{name='普通票';price=199.00;totalQuantity=10})})
} $admin
$eventId=[long]$draft.eventId;$tierId=[long]$draft.sessions[0].ticketTiers[0].ticketTierId
Wait-Ha {
    $snapshot=Api-Ha GET "/api/admin/events/$eventId" $null $admin
    $snapshot.sessions[0].ticketTiers[0].preparationStatus -eq 'READY'
} 'HA活动库存准备' 120
$null=Api-Ha POST "/api/admin/events/$eventId/publish" @{} $admin
$null=Api-Ha GET "/api/events/$eventId"
$request=@{ticketTierId=$tierId;quantity=2;idempotencyKey="ha_order_$suffix"}
$order=Api-Ha POST '/api/orders' $request $token
$number=$order.orderNo
Assert-Ha ($number -match '^[a-f0-9]{32}$') 'HA订单编号格式错误'
$repeated=Api-Ha POST '/api/orders' $request $token
Assert-Ha ($number -eq $repeated.orderNo) 'HA重复下单未返回原编号'
$order=Wait-HaOrder $number 'PENDING_PAYMENT' $token
Assert-Ha ($order.totalAmount -eq 398) 'HA订单金额错误'
$payment=Api-Ha POST "/api/orders/$number/payments" @{} $token
$null=Api-Ha POST "/api/payments/$($payment.paymentNo)/simulate-success" @{} $token
$order=Wait-HaOrder $number 'COMPLETED' $token
$tickets=(Api-Ha GET "/api/orders/$number/tickets" $null $token).tickets
Assert-Ha (@($tickets).Count -eq 2) 'HA电子票数量错误'
$null=Api-Ha POST "/api/payments/$($payment.paymentNo)/simulate-success" @{} $token
Assert-Ha (@((Api-Ha GET "/api/orders/$number/tickets" $null $token).tickets).Count -eq 2) 'HA重复付款导致重复出票'
$primary=Get-HaPrimary
Assert-Ha ((Invoke-HaSql $primary "SELECT CONCAT(available_quantity,',',reserved_quantity,',',sold_quantity) FROM ticket_inventory.t_ticket_stock WHERE ticket_tier_id=$tierId") -eq '8,0,2') 'HA库存事实错误'
Assert-Ha ((Invoke-HaSql $primary "SELECT COUNT(*) FROM ticket_payment.t_outbox_event WHERE aggregate_id='$($payment.paymentNo)' AND status='PUBLISHED'") -eq '1') 'HA Outbox未发布'
Assert-Ha ((Invoke-HaSql $primary "SELECT COUNT(*) FROM ticket_order.t_consumed_event WHERE order_no='$number'") -eq '1') 'HA付款事件未幂等消费'
$report=@{verifiedAt=[DateTime]::UtcNow.ToString('o');eventId=$eventId;orderNo=$number;paymentNo=$payment.paymentNo;ticketCount=2;inventory='8,0,2'}
[IO.File]::WriteAllText((Join-Path $script:HaPrivate 'business-result.json'),($report | ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
Write-Output 'PASS HA完整交易：认证、库存准备、发布、幂等下单、MQ付款通知、出票及数据库事实核对。'
