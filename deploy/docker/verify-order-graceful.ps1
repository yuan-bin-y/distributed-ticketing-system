# 由verify-multi-order.ps1加载；不添加测试业务接口，也不修改业务数据。
function Test-OrderReadRetry([string]$Container,[string]$OrderNo,[string]$Token) {
    $gateway=@(& $script:TicketDocker ps --filter "label=com.docker.compose.project=$ProjectName" --filter 'label=com.docker.compose.service=gateway' --format '{{.Names}}')[0]
    $since=[DateTime]::UtcNow.ToString('o');$failures=0;$maximum=0;$paused=$false
    try {
        # 冻结进程保留注册信息，稳定覆盖“发现列表仍含故障实例”的窗口。
        & $script:TicketDocker pause $Container | Out-Null
        Assert-Ticket ($LASTEXITCODE -eq 0) '无法冻结Order进行读取切换验收。';$paused=$true
        for($i=0;$i -lt 6;$i++) {
            $timer=[Diagnostics.Stopwatch]::StartNew()
            try {$null=Api GET "/api/orders/$OrderNo" $null $Token} catch {$failures++}
            $timer.Stop();$maximum=[Math]::Max($maximum,$timer.Elapsed.TotalMilliseconds)
        }
        $logs=(& $script:TicketDocker logs --since $since $gateway 2>&1) -join "`n"
        $switches=[regex]::Matches($logs,'order.read.retry from=').Count
        Assert-Ticket ($failures -eq 0 -and $switches -gt 0) "冻结实例读取验收失败：请求失败${failures}次，换实例${switches}次。"
        Write-Host "冻结Order后的6次查询全部成功，实际换实例重试${switches}次。"
        return @{requests=6;failures=$failures;retrySwitches=$switches;maxElapsedMs=[Math]::Round($maximum,2)}
    } finally {if($paused){& $script:TicketDocker unpause $Container | Out-Null}}
}

function Test-OrderGraceful([string]$Container,[string]$OrderNo,[string]$Token) {
    $compose=Join-Path $script:TicketProjectRoot 'compose.yml'
    $address=Container-Address $Container
    $gateway=@(& $script:TicketDocker ps --filter "label=com.docker.compose.project=$ProjectName" --filter 'label=com.docker.compose.service=gateway' --format '{{.Names}}')[0]
    $lockJob=$null; $requestJob=$null
    try {
        # 单个验收会话短暂锁表，使GET确实处于处理中。连接退出会自动释放锁。
        $lockJob=Start-ThreadJob -ArgumentList $script:TicketDocker,$compose -ScriptBlock {
            param($docker,$compose)
            & $docker compose -f $compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "LOCK TABLES ticket_order.t_order WRITE; SELECT SLEEP(8); UNLOCK TABLES;"'
        }
        $deadline=[DateTime]::UtcNow.AddSeconds(10)
        do {
            $locked=Sql "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE INFO='SELECT SLEEP(8)'"
            if($locked -eq '1'){break}; Start-Sleep -Milliseconds 100
        } while([DateTime]::UtcNow -lt $deadline)
        Assert-Ticket ($locked -eq '1') '未确认验收会话已取得表锁。'
        $requestJob=Start-ThreadJob -ArgumentList $script:TicketDocker,$gateway,$address,$OrderNo,$Token -ScriptBlock {
            param($docker,$gateway,$address,$number,$token)
            & $docker exec $gateway curl -fsS --max-time 25 "http://${address}:8062/api/orders/$number" -H "Authorization: Bearer $token"
            if($LASTEXITCODE -ne 0){throw '在途GET请求失败。'}
        }
        $deadline=[DateTime]::UtcNow.AddSeconds(5)
        do {
            $waiting=Sql "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE STATE='Waiting for table metadata lock' AND INFO LIKE '%$OrderNo%'"
            if([int]$waiting -gt 0){break}; Start-Sleep -Milliseconds 100
        } while([DateTime]::UtcNow -lt $deadline)
        Assert-Ticket ([int]$waiting -gt 0) '未观察到Order的在途查询，不把空闲停机当作优雅停止验收。'
        $watch=[Diagnostics.Stopwatch]::StartNew()
        & $script:TicketDocker stop --time 30 $Container | Out-Null
        Assert-Ticket ($LASTEXITCODE -eq 0) '正常停止Order失败。'
        $watch.Stop()
        $null=Wait-Job $requestJob -Timeout 10
        Assert-Ticket ($requestJob.State -eq 'Completed') '正常停止中在途请求没有完成。'
        $response=(Receive-Job $requestJob -ErrorAction Stop) -join "`n" | ConvertFrom-Json
        Assert-Ticket ($response.code -eq 'OK' -and $response.data.orderNo -eq $OrderNo) '正常停止时在途查询响应错误。'
        $logs=(& $script:TicketDocker logs --tail 80 $Container 2>&1) -join "`n"
        Assert-Ticket ($logs.Contains('Graceful shutdown complete')) '没有记录优雅停止完成。'
        Write-Host ("正常停止通过：在途GET成功返回，等待退出{0:N2}秒。" -f $watch.Elapsed.TotalSeconds)
        return @{inFlightGet='OK';stopSeconds=[Math]::Round($watch.Elapsed.TotalSeconds,2);shutdown='graceful completed'}
    } finally {
        # 无论验收是否成功，都等待8秒锁会话结束并恢复两个实例。
        if($lockJob){$null=Wait-Job $lockJob -Timeout 15; Remove-Job $lockJob -Force -ErrorAction SilentlyContinue}
        if($requestJob){$null=Wait-Job $requestJob -Timeout 25; Remove-Job $requestJob -Force -ErrorAction SilentlyContinue}
        Invoke-TicketCompose up -d --no-deps --scale order=2 --wait --wait-timeout 180 order
        Wait-Instances 2
    }
}
