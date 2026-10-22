param([string[]]$Databases=@('ticket_auth','ticket_event','ticket_order','ticket_inventory','ticket_payment','nacos'))
. (Join-Path $PSScriptRoot 'common.ps1')
$node=Get-HaPrimary
$directory=Join-Path $script:HaPrivate ('backup-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $directory | Out-Null
$manifest=@()
foreach($database in $Databases) {
    if($database -notmatch '^(ticket_(auth|event|order|inventory|payment|ha_probe)|nacos)$'){throw '备份库名不在本项目允许范围内。'}
    $dump=& $script:HaDocker compose -f $script:HaCompose exec -T $node sh -c 'export MYSQL_PWD="$(cat /run/secrets/mysql-password)"; exec mysqldump -uroot --single-transaction --routines --events --triggers --hex-blob --set-gtid-purged=OFF --default-character-set=utf8mb4 "$1"' sh $database
    if($LASTEXITCODE -ne 0){throw "数据库备份失败：$database；未生成完整manifest。"}
    $file=Join-Path $directory ($database+'.sql')
    [IO.File]::WriteAllText($file,($dump -join "`n"),(New-Object Text.UTF8Encoding($false)))
    $manifest+=@{Database=$database;File=($database+'.sql');SHA256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash}
}
[IO.File]::WriteAllText((Join-Path $directory 'manifest.json'),(ConvertTo-Json -InputObject @($manifest) -Depth 4),(New-Object Text.UTF8Encoding($false)))
Write-Output $directory
