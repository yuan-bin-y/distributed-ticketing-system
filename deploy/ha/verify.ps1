param([string]$JdkHome=$env:JAVA_HOME,[switch]$RestoreCheck)
. (Join-Path $PSScriptRoot 'common.ps1')
if(-not $JdkHome){throw '请设置JAVA_HOME或-JdkHome为JDK17。'}
$results=New-Object System.Collections.Generic.List[string]
$stopped=New-Object System.Collections.Generic.List[string]
function Record-Ha([string]$Message){$results.Add($Message);Write-Output "PASS $Message"}
function Redis-Ha([string]$Node,[string]$Commands) {
    $output=$Commands | & $script:HaDocker compose -f $script:HaCompose exec -T $Node sh -c 'export REDISCLI_AUTH="$(cat /run/secrets/redis-password)"; exec timeout 10 redis-cli --raw -e' 2>&1
    if($LASTEXITCODE -ne 0){throw 'Redis探针失败。'}
    return ($output -join "`n").Trim()
}
function Redis-Master {
    $answer=& $script:HaDocker compose -f $script:HaCompose exec -T sentinel1 redis-cli --raw -p 26379 SENTINEL get-master-addr-by-name ticket-master
    if($LASTEXITCODE -ne 0 -or -not $answer){throw 'Sentinel未返回主节点。'}
    # 停止的容器不会出现在compose ps默认结果里，直接核对声明的固定集群地址。
    switch(@($answer)[0]) {
        '172.30.246.51' {return 'redis1'}
        '172.30.246.52' {return 'redis2'}
        '172.30.246.53' {return 'redis3'}
    }
    throw 'Sentinel返回的节点不属于本次HA集群。'
}
try {
    $primary=Get-HaPrimary
    if((Invoke-HaSql $primary "SELECT COUNT(*) FROM performance_schema.replication_group_members WHERE MEMBER_STATE='ONLINE'") -ne '3'){throw '需要三个MySQL ONLINE成员。'}
    $marker=[Guid]::NewGuid().ToString('N')
    $null=Invoke-HaSql $primary "CREATE DATABASE IF NOT EXISTS ticket_ha_probe; CREATE TABLE IF NOT EXISTS ticket_ha_probe.receipts(id CHAR(32) PRIMARY KEY) ENGINE=InnoDB; INSERT INTO ticket_ha_probe.receipts VALUES('$marker')"
    foreach($router in @('mysql-proxy1','mysql-proxy2')) {
        Wait-Ha { (Invoke-HaSql $primary 'SELECT 1' $router) -eq '1' } "代理可写路由：$router" 30
    }
    Invoke-HaCompose stop $primary;$stopped.Add($primary)
    Wait-Ha { (Get-HaPrimary) -ne $primary } 'MySQL自动选出新主' 60
    $newPrimary=Get-HaPrimary
    if((Invoke-HaSql $newPrimary "SELECT COUNT(*) FROM ticket_ha_probe.receipts WHERE id='$marker'") -ne '1'){throw 'MySQL切换后原提交缺失。'}
    foreach($router in @('mysql-proxy1','mysql-proxy2')) {
        Wait-Ha { (Invoke-HaSql $newPrimary "INSERT IGNORE INTO ticket_ha_probe.receipts VALUES('$marker'); SELECT COUNT(*) FROM ticket_ha_probe.receipts WHERE id='$marker'" $router) -eq '1' } "代理切换后可写：$router" 40
    }
    Record-Ha 'MySQL主节点停止后自动选主，原数据保留，两个代理均可继续写入'
    Invoke-HaCompose start $primary;$stopped.Remove($primary) | Out-Null
    Wait-Ha { (Invoke-HaSql (Get-HaPrimary) "SELECT COUNT(*) FROM performance_schema.replication_group_members WHERE MEMBER_STATE='ONLINE'") -eq '3' } 'MySQL旧主重新加入' 120
    Record-Ha 'MySQL旧主重启后重新加入，未执行强制成员覆盖'

    $master=Redis-Master
    Wait-Ha { (Redis-Ha $master "SET ticket:ha:probe:$marker $marker`nWAIT 2 5000") -match '2$' } 'Redis两个副本确认探针写入' 30
    Invoke-HaCompose stop $master;$stopped.Add($master)
    Wait-Ha { (Redis-Master) -ne $master } 'Sentinel切换Redis主节点' 60
    $newMaster=Redis-Master
    if((Redis-Ha $newMaster "GET ticket:ha:probe:$marker") -ne $marker){throw 'Redis切换后探针数据缺失。'}
    Wait-Ha { (Redis-Ha $newMaster "SET ticket:ha:after:$marker ok") -eq 'OK' } 'Redis新主恢复写入' 30
    Record-Ha 'Redis主节点停止后Sentinel自动切换，已复制数据保留且新主可写'
    Invoke-HaCompose start $master;$stopped.Remove($master) | Out-Null

    $probe=Join-Path $script:HaPrivate 'probe'
    New-Item -ItemType Directory -Force -Path $probe | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jar=[IO.Compression.ZipFile]::OpenRead((Join-Path $script:TicketProjectRoot 'ticket-order-service/target/ticket-order-service-1.0-SNAPSHOT.jar'))
    try {
        # 使用当前应用的实际依赖集合，包含新版AMQP客户端构造器所需的Netty类。
        foreach($entry in $jar.Entries | Where-Object {$_.FullName -like 'BOOT-INF/lib/*.jar'}) {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $probe $entry.Name),$true)
        }
    }finally{$jar.Dispose()}
    & (Join-Path $JdkHome 'bin/javac.exe') -encoding UTF-8 -cp "$probe/*" -d $probe (Join-Path $PSScriptRoot 'HaRabbitProbe.java')
    if($LASTEXITCODE -ne 0){throw 'RabbitMQ探针编译失败。'}
    $queue='ticket-ha-probe-'+$marker
    $network=$env:TICKET_HA_PROJECT+'_default'
    $secret=Join-Path $script:HaPrivate 'rabbit-password'
    function Rabbit-Probe([string]$Mode) {
        & $script:HaDocker run --rm --network $network --mount "type=bind,source=$probe,target=/probe,readonly" --mount "type=bind,source=$secret,target=/run/secrets/rabbit-password,readonly" eclipse-temurin:17-jre-jammy java -cp '/probe:/probe/*' HaRabbitProbe $Mode $queue $marker
        if($LASTEXITCODE -ne 0){throw 'RabbitMQ quorum探针失败。'}
    }
    Rabbit-Probe prepare
    Invoke-HaCompose stop rabbit1;$stopped.Add('rabbit1')
    Wait-Ha { Rabbit-Probe verify; $true } 'RabbitMQ quorum队列Leader切换' 60
    Record-Ha 'RabbitMQ队列Leader停止后原持久消息保留，仍可确认发布和消费'
    Invoke-HaCompose start rabbit1;$stopped.Remove('rabbit1') | Out-Null

    $password=[IO.File]::ReadAllText((Join-Path $script:HaPrivate 'nacos-admin-password')).Trim()
    $login=Invoke-HaNacos nacos1 POST '/nacos/v3/auth/user/login' ('username=nacos&password='+[Uri]::EscapeDataString($password))
    $access=[Uri]::EscapeDataString($login.accessToken)
    $null=Invoke-HaNacos nacos1 POST ('/nacos/v1/cs/configs?accessToken='+$access) ('dataId=ha-probe.properties&group=TICKET_HA&type=properties&content='+$marker)
    Invoke-HaCompose stop nacos1;$stopped.Add('nacos1')
    Wait-Ha { (Invoke-HaNacos nacos2 GET ('/nacos/v1/cs/configs?dataId=ha-probe.properties&group=TICKET_HA&accessToken='+$access) '' -Raw) -eq $marker } 'Nacos节点停止后其余节点仍能读取配置' 60
    Record-Ha 'Nacos一个节点停止后其余节点仍能鉴权并读取共享配置'
    Invoke-HaCompose start nacos1;$stopped.Remove('nacos1') | Out-Null
    $backup=& (Join-Path $PSScriptRoot 'backup.ps1') -Databases @('ticket_ha_probe')
    if($RestoreCheck) {
        & (Join-Path $PSScriptRoot 'restore-check.ps1') -BackupDirectory $backup
        if((Invoke-HaSql mysql-restore "SELECT COUNT(*) FROM ticket_ha_probe.receipts WHERE id='$marker'") -ne '1') {throw '隔离恢复库缺少本次已提交的探针记录。'}
        Record-Ha '备份校验和与原记录通过，并已在独立MySQL数据卷中恢复'
    }
    $results | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $script:HaPrivate 'verification-result.json') -Encoding utf8
    Write-Output 'HA节点故障验证完成，结果保存在.local/ha/verification-result.json。'
} finally {
    foreach($node in $stopped) {Invoke-HaCompose start $node}
}
