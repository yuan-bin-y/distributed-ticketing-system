[CmdletBinding()]
param([string]$ProjectName='ticket-platform')
. (Join-Path $PSScriptRoot 'verify.ps1') -ProjectName $ProjectName -LibraryOnly
$credential=[IO.File]::ReadAllText((Join-Path $script:TicketProjectRoot '.local/service-credentials/order-inventory.token')).Trim()
$inventoryPort=if($env:TICKET_INVENTORY_PORT){$env:TICKET_INVENTORY_PORT}else{'18063'}
$base="http://127.0.0.1:$inventoryPort/internal/stock-reservations"
$suffix=[Guid]::NewGuid().ToString('N')
$tier=7000000000000+[Math]::Abs([BitConverter]::ToInt32([Guid]::NewGuid().ToByteArray(),0))
$expires=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow,'China Standard Time').AddMinutes(30).ToString('yyyy-MM-ddTHH:mm:ss')
$headers=@{'X-Order-Inventory-Credential'=$credential}
try {
    $null=Sql "INSERT INTO ticket_inventory.t_ticket_stock(ticket_tier_id,session_id,total_quantity,available_quantity) VALUES($tier,$tier,10,10)"
    # 初次突发直接记录503；后续只以原编号重试，不新建关联订单。
    $results=@(1..50|ForEach-Object -Parallel {
        $body=@{orderId="hot_$($using:suffix)_$_";sessionId=$using:tier;ticketTierId=$using:tier;quantity=1;expiresAt=$using:expires}|ConvertTo-Json -Compress
        $busy=0;$attempts=0
        do {
            $attempts++
            $response=Invoke-WebRequest -Uri $using:base -Method POST -Headers $using:headers -Body $body -ContentType 'application/json' -SkipHttpErrorCheck -TimeoutSec 15
            if($response.StatusCode -eq 503){$busy++;Start-Sleep -Milliseconds (Get-Random -Minimum 40 -Maximum 140)}
        }while($response.StatusCode -eq 503 -and $attempts -lt 200)
        [pscustomobject]@{status=[int]$response.StatusCode;busy=$busy;body=($response.Content|ConvertFrom-Json);originalBody=$body}
    } -ThrottleLimit 50)
    $success=@($results|Where-Object status -eq 200)
    $conflict=@($results|Where-Object status -eq 409)
    $busy=($results|Measure-Object busy -Sum).Sum
    Assert-Ticket ($success.Count -eq 10 -and $conflict.Count -eq 40) '预留数量或拒绝数量不符合10张库存'
    Assert-Ticket ($busy -gt 0) '未观察到实际热点准入拒绝'
    Assert-Ticket ((Sql "SELECT CONCAT(available_quantity,',',reserved_quantity,',',sold_quantity) FROM ticket_inventory.t_ticket_stock WHERE ticket_tier_id=$tier") -eq '0,10,0') '发生超卖或库存计数不一致'
    foreach($result in $success) {
        $repeat=Invoke-RestMethod -Uri $base -Method POST -Headers $headers -Body $result.originalBody -ContentType 'application/json' -TimeoutSec 15
        Assert-Ticket ($repeat.data.reservationId -eq $result.body.data.reservationId) '重复请求产生了新的预留'
        $id=$repeat.data.reservationId
        $null=Invoke-RestMethod "$base/$id/release" -Method POST -Headers $headers -TimeoutSec 15
        $null=Invoke-RestMethod "$base/$id/release" -Method POST -Headers $headers -TimeoutSec 15
    }
    Assert-Ticket ((Sql "SELECT CONCAT(available_quantity,',',reserved_quantity,',',sold_quantity) FROM ticket_inventory.t_ticket_stock WHERE ticket_tier_id=$tier") -eq '10,0,0') '释放后库存未守恒'
    $report=@{status='PASS';requests=50;reserved=10;stockConflicts=40;admissionRejections=$busy;idempotentRepeats=10;released=10;verifiedAt=[DateTime]::UtcNow.ToString('o')}
    $report|ConvertTo-Json|Set-Content (Join-Path $script:TicketProjectRoot '.local/docker/hot-stock-result.json')
    Write-Output "PASS hot stock: 50 requests, 10 reservations, 40 stock conflicts, $busy transient admission rejections; idempotence and release balance verified"
} finally {
    $null=Sql "DELETE FROM ticket_inventory.t_stock_reservation WHERE ticket_tier_id=$tier AND order_id LIKE 'hot_${suffix}_%'; DELETE FROM ticket_inventory.t_ticket_stock WHERE ticket_tier_id=$tier"
}
