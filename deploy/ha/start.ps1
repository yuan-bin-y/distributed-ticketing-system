param([switch]$WithApplications,[ValidateRange(1,2)][int]$ApplicationReplicas=2,[string]$NacosHome,[string]$JdkHome=$env:JAVA_HOME)
. (Join-Path $PSScriptRoot 'common.ps1')
$env:TICKET_HA_APP_REPLICAS=[string]$ApplicationReplicas
Initialize-HaSecrets
if(-not $NacosHome){$NacosHome=Join-Path $script:TicketProjectRoot '.local/nacos-3.1.2/nacos'}
foreach($required in @('target/nacos-server.jar','conf/mysql-schema.sql')) {
    if(-not (Test-Path -LiteralPath (Join-Path $NacosHome $required))){throw '请用-NacosHome指定Nacos3.1.2官方发行包。'}
}
$context=Join-Path $script:TicketProjectRoot '.local/docker/nacos'
New-Item -ItemType Directory -Force -Path $context | Out-Null
Copy-Item -LiteralPath (Join-Path $NacosHome 'target/nacos-server.jar') -Destination (Join-Path $context 'nacos-server.jar') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../docker/nacos/application.properties') -Destination (Join-Path $context 'application.properties') -Force
Invoke-HaCompose config --quiet
Invoke-HaCompose build mysql-proxy1 nacos1
Invoke-HaCompose up -d --wait --wait-timeout 240 mysql1 mysql2 mysql3 rabbit1 rabbit2 rabbit3 redis1 redis2 redis3 sentinel1 sentinel2 sentinel3
$marker=Join-Path $script:HaPrivate ($env:TICKET_HA_PROJECT+'-mysql-initialized')
if(-not (Test-Path -LiteralPath $marker)) {
    $existing=Invoke-HaSql mysql1 "SELECT COUNT(*) FROM performance_schema.replication_group_members WHERE MEMBER_STATE='ONLINE'"
    if($existing -eq '0') {
        foreach($node in @('mysql1','mysql2','mysql3')) {
            $databases=Invoke-HaSql $node "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name NOT IN ('mysql','information_schema','performance_schema','sys')"
            if($databases -ne '0'){throw '检测到已有业务库，拒绝初始化或RESET MASTER；先核对现有集群。'}
        }
        $recovery=[IO.File]::ReadAllText((Join-Path $script:HaPrivate 'mysql-recovery-password')).Trim()
        foreach($node in @('mysql1','mysql2','mysql3')) {
            $null=Invoke-HaSql $node "RESET MASTER; SET SQL_LOG_BIN=0; CREATE USER IF NOT EXISTS 'ha_recovery'@'%' IDENTIFIED BY '$recovery'; ALTER USER 'ha_recovery'@'%' IDENTIFIED BY '$recovery'; GRANT REPLICATION SLAVE, CONNECTION_ADMIN, BACKUP_ADMIN ON *.* TO 'ha_recovery'@'%'; SET SQL_LOG_BIN=1; SET PERSIST group_replication_recovery_get_public_key=ON; CHANGE REPLICATION SOURCE TO SOURCE_USER='ha_recovery',SOURCE_PASSWORD='$recovery' FOR CHANNEL 'group_replication_recovery';"
        }
        try {$null=Invoke-HaSql mysql1 'SET GLOBAL group_replication_bootstrap_group=ON; START GROUP_REPLICATION' -MaxSeconds 120}
        finally {$null=Invoke-HaSql mysql1 'SET GLOBAL group_replication_bootstrap_group=OFF'}
    }
    foreach($node in @('mysql2','mysql3')) {
        $state=Invoke-HaSql $node "SELECT COUNT(*) FROM performance_schema.replication_group_members WHERE MEMBER_ID=@@server_uuid AND MEMBER_STATE='ONLINE'"
        if($state -eq '0'){$null=Invoke-HaSql $node 'START GROUP_REPLICATION' -MaxSeconds 120}
    }
}
Wait-Ha { (Invoke-HaSql (Get-HaPrimary) "SELECT COUNT(*) FROM performance_schema.replication_group_members WHERE MEMBER_STATE='ONLINE'") -eq '3' } 'MySQL三个ONLINE成员'
foreach($node in @('mysql1','mysql2','mysql3')) {$null=Invoke-HaSql $node 'SET PERSIST group_replication_start_on_boot=ON'}
[IO.File]::WriteAllText($marker,'initialized')
Invoke-HaCompose up -d mysql-proxy1 mysql-proxy2
$primary=Get-HaPrimary
$hasNacos=Invoke-HaSql $primary "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='nacos'"
if($hasNacos -eq '0') {
    $null=Invoke-HaSql $primary 'CREATE DATABASE nacos CHARACTER SET utf8mb4'
    $schema=Get-Content -LiteralPath (Join-Path $NacosHome 'conf/mysql-schema.sql') -Raw
    $null=Invoke-HaSql $primary ("USE nacos;`n"+$schema)
}
Invoke-HaCompose up -d nacos1 nacos2 nacos3
foreach($node in @('nacos1','nacos2','nacos3')) {
    Wait-Ha { $null -ne (Invoke-HaNacos $node GET '/nacos/v3/admin/core/state/readiness') } "$node 就绪" 240
}
$admin=[IO.File]::ReadAllText((Join-Path $script:HaPrivate 'nacos-admin-password')).Trim()
try {$null=Invoke-HaNacos nacos1 POST '/nacos/v3/auth/user/admin' ("password="+[Uri]::EscapeDataString($admin))} catch {}
$login=Invoke-HaNacos nacos1 POST '/nacos/v3/auth/user/login' ("username=nacos&password="+[Uri]::EscapeDataString($admin))
$access=[Uri]::EscapeDataString($login.accessToken)
if(-not $login.accessToken){throw 'Nacos认证失败；禁止清空已有账号或覆盖密码。'}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '../nacos') -Filter '*.yaml') {
    $body='dataId='+[Uri]::EscapeDataString($file.Name)+'&group=TICKET_HA&type=yaml&content='+[Uri]::EscapeDataString([IO.File]::ReadAllText($file.FullName))
    $null=Invoke-HaNacos nacos1 POST ("/nacos/v1/cs/configs?accessToken="+$access) $body
}
if($WithApplications) {
    & (Join-Path $PSScriptRoot '../capacity/check-capacity.ps1') -MySqlMaxConnections 300 -ReservedConnections 60 -EventInstances $ApplicationReplicas -EventConcurrency 8 -AuthInstances $ApplicationReplicas -OrderInstances $ApplicationReplicas -InventoryInstances $ApplicationReplicas -PaymentInstances $ApplicationReplicas -NacosInstances 3
    if(-not $JdkHome){throw '业务镜像准备需JDK17，请设置JAVA_HOME。'}
    Push-Location $script:TicketProjectRoot
    try {
        foreach($module in @('ticket-auth-service','ticket-event-service','ticket-order-service','ticket-inventory-service','ticket-payment-service','ticket-gateway')) {
            if(-not (Test-Path -LiteralPath "$module/target/$module-1.0-SNAPSHOT.jar")){throw '请先执行Maven package构建最新业务JAR。'}
        }
        if(-not (Test-Path -LiteralPath '.local/auth-keys/private.pem')) { & (Join-Path $JdkHome 'bin/java.exe') deploy/auth/GenerateAuthKeys.java;if($LASTEXITCODE -ne 0){throw '密钥生成失败。'} }
        foreach($key in @('private.pem','public.pem')) {if(-not (Test-Path -LiteralPath ".local/auth-keys/$key")){throw 'Auth密钥不完整，先核对已有密钥；禁止覆盖。'}}
        foreach($credential in @('order-event','order-inventory','event-inventory','order-payment','payment-order')) {
            if(-not (Test-Path -LiteralPath ".local/service-credentials/$credential.token")) { & (Join-Path $JdkHome 'bin/java.exe') deploy/auth/GenerateServiceCredential.java ".local/service-credentials/$credential.token";if($LASTEXITCODE -ne 0){throw '凭证生成失败。'} }
        }
    }finally{Pop-Location}
    Invoke-HaCompose --profile apps build
    Invoke-HaCompose --profile apps up -d --wait --wait-timeout 360
}
Write-Output 'HA集群已启动；业务入口（启用apps后）：http://localhost:18070 和 http://localhost:18071。私有配置保存在.local/ha。'
