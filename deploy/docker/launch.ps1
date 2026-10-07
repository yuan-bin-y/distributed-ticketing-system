[CmdletBinding()]
param([ValidateSet('Start','Rebuild','Stop','Status')][string]$Action='Start')

$ErrorActionPreference='Stop'
$OutputEncoding=New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding=$OutputEncoding

try {
    . (Join-Path $PSScriptRoot 'common.ps1')
    $taskDocker=Get-TicketDocker

    function Test-TicketEngine {
        # Docker未运行时只使用退出码判断，不把原生stderr变成PowerShell异常。
        $ErrorActionPreference='Continue'
        & $taskDocker info --format '{{.OSType}}' *> $null
        return $LASTEXITCODE -eq 0
    }

    if(-not (Test-TicketEngine)) {
        if($Action -in @('Stop','Status')){throw 'Docker尚未运行，请先双击启动项目.cmd。'}
        $taskCandidates=@(
            (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/Docker Desktop.exe'),
            (Join-Path $env:ProgramFiles 'Docker/Docker/Docker Desktop.exe'),
            ([IO.Path]::GetFullPath((Join-Path (Split-Path $taskDocker -Parent) '../../Docker Desktop.exe')))
        )
        $taskDesktop=$taskCandidates | Where-Object {Test-Path -LiteralPath $_} | Select-Object -First 1
        if(-not $taskDesktop){throw '未找到Docker Desktop，请手动打开Docker Desktop后再次启动。'}
        Write-Host '正在启动Docker Desktop，等待引擎就绪……'
        Start-Process -FilePath $taskDesktop -WindowStyle Hidden | Out-Null
        $taskDeadline=[DateTime]::UtcNow.AddMinutes(3)
        while(-not (Test-TicketEngine)) {
            if([DateTime]::UtcNow -ge $taskDeadline){throw 'Docker引擎在3分钟内未就绪，请打开Docker Desktop查看原因后重试。'}
            Start-Sleep -Seconds 2
        }
    }

    switch($Action) {
        'Start' {
            Write-Host '使用已有JAR启动整套项目（两个Order实例）……'
            & (Join-Path $PSScriptRoot 'start.ps1') -SkipPackage -OrderReplicas 2
        }
        'Rebuild' {
            Write-Host '重新打包Java、构建镜像并启动整套项目（两个Order实例）……'
            & (Join-Path $PSScriptRoot 'start.ps1') -OrderReplicas 2
        }
        'Stop' {
            & (Join-Path $PSScriptRoot 'stop.ps1')
            Write-Host '项目已停止，数据库等数据卷已保留。'
        }
        'Status' { & (Join-Path $PSScriptRoot 'status.ps1') }
    }
    exit 0
} catch {
    Write-Host ('操作失败：'+$_.Exception.Message) -ForegroundColor Red
    exit 1
}
