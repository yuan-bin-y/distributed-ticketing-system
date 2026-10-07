param([string]$ProjectName='ticket-platform')
. (Join-Path $PSScriptRoot 'common.ps1')
$script:TicketDocker=Get-TicketDocker
Initialize-TicketDockerEnvironment $ProjectName
# 删除本项目容器与网络，保留所有数据卷；不提供自动清空数据选项。
Invoke-TicketCompose down
