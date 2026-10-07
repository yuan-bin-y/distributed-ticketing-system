$ErrorActionPreference='Stop'
$script:TicketProjectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))

function Get-TicketDocker {
    $command=Get-Command docker -ErrorAction SilentlyContinue
    if($command){return $command.Source}
    foreach($desktop in @(
        (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),
        (Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe')
    )) {
        if(Test-Path -LiteralPath $desktop){return $desktop}
    }
    throw '没有找到Docker，请安装并启动Docker Desktop。'
}

function Read-TicketVariable([string]$Name) {
    foreach($scope in @('Process','User','Machine')) {
        $value=[Environment]::GetEnvironmentVariable($Name,$scope)
        if(-not [string]::IsNullOrEmpty($value)){return $value}
    }
    return $null
}

function Get-TicketLocalSecret([string]$Name) {
    $directory=Join-Path $script:TicketProjectRoot '.local/docker'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $path=Join-Path $directory $Name
    if(-not (Test-Path -LiteralPath $path)) {
        $random=[Security.Cryptography.RandomNumberGenerator]::Create()
        try { $bytes=New-Object byte[] 32; $random.GetBytes($bytes) } finally { $random.Dispose() }
        $secret=[Convert]::ToBase64String($bytes)
        [IO.File]::WriteAllText($path,$secret,(New-Object Text.UTF8Encoding($false)))
    }
    return [IO.File]::ReadAllText($path).Trim()
}

function Initialize-TicketDockerEnvironment([string]$ProjectName='ticket-platform') {
    if($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]*$'){throw 'Compose项目名只能包含小写字母、数字、下划线和连字符。'}
    $password=Read-TicketVariable 'LOCAL_MYSQL_PASSWORD'
    if([string]::IsNullOrEmpty($password)){throw 'Windows环境变量LOCAL_MYSQL_PASSWORD尚未设置。'}
    $env:LOCAL_MYSQL_PASSWORD=$password
    $env:TICKET_DOCKER_PROJECT=$ProjectName
    $env:TICKET_DOCKER_RABBITMQ_PASSWORD=Get-TicketLocalSecret 'rabbitmq.password'
    $env:TICKET_DOCKER_ADMIN_USERNAME='docker_admin'
    $env:TICKET_DOCKER_ADMIN_PASSWORD=Get-TicketLocalSecret 'admin.password'
}

function Invoke-TicketCompose {
    & $script:TicketDocker compose -f (Join-Path $script:TicketProjectRoot 'compose.yml') @args
    if($LASTEXITCODE -ne 0){throw 'Docker Compose执行失败；查看上一条错误及对应服务日志。'}
}
