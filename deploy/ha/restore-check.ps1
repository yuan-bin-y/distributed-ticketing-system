param([Parameter(Mandatory=$true)][string]$BackupDirectory)
. (Join-Path $PSScriptRoot 'common.ps1')
$backup=[IO.Path]::GetFullPath($BackupDirectory)
$allowed=[IO.Path]::GetFullPath($script:HaPrivate)+[IO.Path]::DirectorySeparatorChar
if(-not $backup.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw '只接受本项目.local/ha中的备份目录。'}
$manifest=@(Get-Content -LiteralPath (Join-Path $backup 'manifest.json') -Raw | ConvertFrom-Json)
if($manifest.Count -eq 0){throw '备份清单为空。'}
foreach($entry in $manifest) {
    if($entry.Database -notmatch '^(ticket_(auth|event|order|inventory|payment|ha_probe)|nacos)$' -or $entry.File -cne ($entry.Database+'.sql')){throw '备份清单包含非法路径或库名。'}
    if((Get-FileHash -LiteralPath (Join-Path $backup $entry.File) -Algorithm SHA256).Hash -ne $entry.SHA256){throw '备份校验和不匹配，拒绝恢复。'}
}
Invoke-HaCompose --profile restore up -d --wait --wait-timeout 180 mysql-restore
foreach($entry in $manifest) {
    $exists=Invoke-HaSql mysql-restore ("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+$entry.Database+"'")
    if($exists -ne '0'){throw '隔离恢复实例已有同名数据库，拒绝覆盖；原HA主库始终不作为恢复目标。'}
}
foreach($entry in $manifest) {
    $database=$entry.Database
    $null=Invoke-HaSql mysql-restore ("CREATE DATABASE "+$database+" CHARACTER SET utf8mb4")
    $sql=[IO.File]::ReadAllText((Join-Path $backup $entry.File))
    $null=Invoke-HaSql mysql-restore ("USE "+$database+";`n"+$sql) -MaxSeconds 300
    $tables=Invoke-HaSql mysql-restore ("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"+$database+"'")
    Write-Output "恢复校验通过：$database，表数量=$tables；目标为独立mysql-restore数据卷。"
}
