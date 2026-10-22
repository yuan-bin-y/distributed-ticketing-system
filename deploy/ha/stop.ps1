. (Join-Path $PSScriptRoot 'common.ps1')
Invoke-HaCompose --profile apps --profile restore stop
Write-Output 'HA学习集群已停止，数据卷和.local中的凭证、备份均保留。'
