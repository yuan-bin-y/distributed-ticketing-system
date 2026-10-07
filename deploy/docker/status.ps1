param([string]$ProjectName='ticket-platform',[string]$Service)
. (Join-Path $PSScriptRoot 'common.ps1')
$script:TicketDocker=Get-TicketDocker
Initialize-TicketDockerEnvironment $ProjectName
if($Service){Invoke-TicketCompose logs --tail 100 $Service}
else{Invoke-TicketCompose ps}
