[CmdletBinding()]
param([string]$ProjectName='ticket-platform',[switch]$FaultTest)
. (Join-Path $PSScriptRoot 'common.ps1')
$script:TicketDocker=Get-TicketDocker
Initialize-TicketDockerEnvironment $ProjectName
$promPort=if($env:TICKET_PROMETHEUS_PORT){$env:TICKET_PROMETHEUS_PORT}else{'19090'}
$grafanaPort=if($env:TICKET_DOCKER_GRAFANA_PORT){$env:TICKET_DOCKER_GRAFANA_PORT}else{'3002'}
$base="http://127.0.0.1:$promPort"
function Query([string]$Expression) {
    $response=Invoke-RestMethod "$base/api/v1/query?query=$([Uri]::EscapeDataString($Expression))" -TimeoutSec 10
    if($response.status -ne 'success'){throw 'Prometheus查询失败'}
    return @($response.data.result)
}
function Require($Condition,[string]$Message){if(-not $Condition){throw $Message}}
$deadline=[DateTime]::UtcNow.AddSeconds(90)
do {
    $targets=@(Query 'up{job="ticket-services"}')
    if($targets.Count -ge 6 -and @($targets|Where-Object {$_.value[1] -ne '1'}).Count -eq 0){break}
    Start-Sleep -Seconds 2
}while([DateTime]::UtcNow -lt $deadline)
Require ($targets.Count -ge 6) '未采集到六个服务'
Require (@($targets|Where-Object {$_.value[1] -ne '1'}).Count -eq 0) '存在抓取失败的服务'
$services=@($targets.metric.service|Sort-Object -Unique)
Require ($services.Count -eq 6) '发现的服务种类不足六个'
Require (@(Query 'ticket_workflow_pending{workflow="payment_outbox"}').Count -eq 1) 'Outbox业务指标缺失'
Require (@(Query 'ticket_workflow_pending{workflow="order_recovery"}').Count -ge 1) '订单恢复指标缺失'
Require (@(Query 'ticket_mq_queue_messages{queue="dead"}').Count -ge 1) '死信队列指标缺失'
Require (@(Query 'ticket_workflow_collector_healthy == 0').Count -eq 0) '业务采样存在失败'
Require (@(Query 'ticket_mq_collector_healthy == 0').Count -eq 0) 'MQ队列采样存在失败'
$dashboard=Invoke-RestMethod "http://127.0.0.1:$grafanaPort/api/dashboards/uid/ticket-platform-overview" -TimeoutSec 10
Require ($dashboard.dashboard.panels.Count -eq 11) 'Grafana业务面板未加载'
$rules=Invoke-RestMethod "$base/api/v1/rules" -TimeoutSec 10
Require (@($rules.data.groups|ForEach-Object {$_.rules}).Count -eq 10) '告警规则未全部加载'
# 指标只能从Compose内网管理端口读取，业务端口不允许匿名读取。
foreach($port in @(18060,18061,18063,18064,18065)) {
    $response=Invoke-WebRequest "http://127.0.0.1:$port/actuator/prometheus" -SkipHttpErrorCheck -TimeoutSec 10
    Require ($response.StatusCode -in @(401,403,404)) "业务端口$port公开了指标"
}
$fired=$false;$resolved=$false
if($FaultTest) {
    $id=(& $script:TicketDocker compose -f (Join-Path $script:TicketProjectRoot 'compose.yml') ps -q inventory).Trim()
    Require (-not [string]::IsNullOrWhiteSpace($id)) '找不到库存容器'
    try {
        & $script:TicketDocker pause $id | Out-Null
        Require ($LASTEXITCODE -eq 0) '故障注入失败'
        $deadline=[DateTime]::UtcNow.AddSeconds(70)
        do {
            $fired=@(Query 'ALERTS{alertname="TicketServiceUnavailable",service="inventory",alertstate="firing"}').Count -gt 0
            if($fired){break};Start-Sleep -Seconds 2
        }while([DateTime]::UtcNow -lt $deadline)
        Require $fired '库存不可用告警未触发'
    }finally{& $script:TicketDocker unpause $id | Out-Null}
    $deadline=[DateTime]::UtcNow.AddSeconds(45)
    do {
        $resolved=@(Query 'ALERTS{alertname="TicketServiceUnavailable",service="inventory",alertstate="firing"}').Count -eq 0
        if($resolved){break};Start-Sleep -Seconds 2
    }while([DateTime]::UtcNow -lt $deadline)
    Require $resolved '恢复后告警未消退'
}
$result=@{status='PASS';targets=$targets.Count;services=$services;dashboardPanels=11;alertRules=10;faultInjected=[bool]$FaultTest;alertFired=$fired;alertResolved=$resolved;verifiedAt=[DateTime]::UtcNow.ToString('o')}
$reportName=if($FaultTest){'monitoring-fault-result.json'}else{'monitoring-result.json'}
$result|ConvertTo-Json -Depth 5|Set-Content (Join-Path $script:TicketProjectRoot ".local/docker/$reportName")
Write-Output "PASS monitoring: $($targets.Count) targets, 6 services, 11 panels, 10 rules; fault fired=$fired resolved=$resolved"
