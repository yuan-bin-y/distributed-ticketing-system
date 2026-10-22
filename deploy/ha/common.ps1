$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../docker/common.ps1')
$script:HaDocker=Get-TicketDocker
$script:HaCompose=Join-Path $PSScriptRoot 'compose.yml'
$script:HaApps=Join-Path $PSScriptRoot 'compose.apps.yml'
$script:HaPrivate=Join-Path $script:TicketProjectRoot '.local/ha'
if(-not $env:TICKET_HA_PROJECT){$env:TICKET_HA_PROJECT='ticket-ha'}
if($env:TICKET_HA_PROJECT -notmatch '^ticket-ha(?:-[a-z0-9_-]+)?$'){throw 'HA项目名必须以ticket-ha开头，避免操作其他Compose项目。'}
function Initialize-HaSecrets {
    New-Item -ItemType Directory -Force -Path $script:HaPrivate | Out-Null
    foreach($name in @('mysql-password','mysql-recovery-password','redis-password','rabbit-password','rabbit-cookie','nacos-identity','nacos-token-secret','nacos-admin-password','auth-admin-password')) {
        $path=Join-Path $script:HaPrivate $name
        if(-not (Test-Path -LiteralPath $path)) {
            $length=32
            if($name -eq 'mysql-recovery-password'){$length=16}
            $bytes=New-Object byte[] $length
            $random=[Security.Cryptography.RandomNumberGenerator]::Create()
            try {$random.GetBytes($bytes)} finally {$random.Dispose()}
            [IO.File]::WriteAllText($path,([BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()),(New-Object Text.UTF8Encoding($false)))
        }
    }
}
function Invoke-HaCompose {
    & $script:HaDocker compose -f $script:HaCompose -f $script:HaApps @args
    if($LASTEXITCODE -ne 0){throw 'HA Compose操作失败。'}
}
function Invoke-HaSql([string]$Node,[string]$Query,[string]$Router='',[int]$MaxSeconds=15) {
    if($Node -notmatch '^(mysql[123]|mysql-restore)$'){throw '不支持的HA数据库节点。'}
    if($Router -and $Router -notmatch '^mysql-proxy[12]$'){throw '数据库代理名无效。'}
    if($MaxSeconds -lt 1 -or $MaxSeconds -gt 600){throw '数据库探针超时范围为1至600秒。'}
    # 原生客户端也必须有执行期限，否则网络断开可能让外层Wait-Ha无法检查截止时间。
    $command='export MYSQL_PWD="$(cat /run/secrets/mysql-password)"; exec timeout '+$MaxSeconds+' mysql --connect-timeout=3 -uroot --batch --skip-column-names'
    if($Router){$command+=' -h '+$Router}
    $result=$Query | & $script:HaDocker compose -f $script:HaCompose exec -T $Node sh -c $command 2>&1
    if($LASTEXITCODE -ne 0) {
        [IO.File]::WriteAllText((Join-Path $script:HaPrivate 'last-sql-error.log'),($result -join [Environment]::NewLine))
        throw "HA数据库操作失败：$Node；详细错误保存在本地.local/ha/last-sql-error.log。"
    }
    return ($result -join "`n").Trim()
}
function Wait-Ha([scriptblock]$Check,[string]$Description,[int]$Seconds=180) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try {if(& $Check){return}} catch {}
        Start-Sleep -Seconds 2
    } while([DateTime]::UtcNow -lt $deadline)
    throw "等待超时：$Description"
}
function Get-HaPrimary {
    foreach($node in @('mysql1','mysql2','mysql3')) {
        try {
            $primary=Invoke-HaSql $node "SELECT MEMBER_HOST FROM performance_schema.replication_group_members WHERE MEMBER_STATE='ONLINE' AND MEMBER_ROLE='PRIMARY'"
            if($primary -match '^mysql[123]$'){return $primary}
        }catch{}
    }
    throw '找不到多数派中的MySQL主节点；禁止自动强制引导或清空GTID。'
}
function Invoke-HaNacos([string]$Node,[string]$Method,[string]$Path,[string]$Body='',[switch]$Raw) {
    if($Node -notmatch '^nacos[123]$' -or $Path.Contains('"') -or $Body.Contains('"')){throw 'Nacos操作参数无效。'}
    $config="url = `"http://127.0.0.1:8848$Path`"`nrequest = `"$Method`"`n"
    if($Body){$config+="header = `"Content-Type: application/x-www-form-urlencoded`"`ndata = `"$Body`"`n"}
    $output=$config | & $script:HaDocker compose -f $script:HaCompose exec -T $Node curl --connect-timeout 3 --max-time 15 --silent --show-error --fail-with-body --config - 2>&1
    if($LASTEXITCODE -ne 0){
        $safe=$output -join "`n"
        foreach($file in Get-ChildItem -LiteralPath $script:HaPrivate -File | Where-Object {$_.Length -le 128 -and $_.Name -notlike '*.log'}) {
            $value=[IO.File]::ReadAllText($file.FullName).Trim()
            if($value.Length -ge 16){$safe=$safe.Replace($value,'[redacted]')}
        }
        $safe=$safe -replace 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+','[redacted-token]'
        [IO.File]::WriteAllText((Join-Path $script:HaPrivate 'last-nacos-error.log'),$safe)
        throw "Nacos请求失败：$Node $Method；脱敏错误保存在.local/ha/last-nacos-error.log。"
    }
    if($Raw){return ($output -join "`n").Trim()}
    return (($output -join "`n") | ConvertFrom-Json)
}
