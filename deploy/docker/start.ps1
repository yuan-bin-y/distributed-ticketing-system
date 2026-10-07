[CmdletBinding()]
param(
    [switch]$SkipPackage,
    [string]$JdkHome,
    [string]$MavenCommand,
    [string]$ProjectName='ticket-platform',
    [string]$NacosHome,
    [ValidateRange(1,8)][int]$OrderReplicas=1
)
. (Join-Path $PSScriptRoot 'common.ps1')
$script:TicketDocker=Get-TicketDocker
Initialize-TicketDockerEnvironment $ProjectName
Push-Location $script:TicketProjectRoot
try {
    & $script:TicketDocker info --format '{{.OSType}}' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Docker引擎未运行，请启动Docker Desktop。'}
    if(-not $JdkHome){$JdkHome=Read-TicketVariable 'JAVA_HOME'}
    if(-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe'))) {
        $installed=Join-Path $env:USERPROFILE '.jdks/ms-17.0.20'
        if(Test-Path -LiteralPath (Join-Path $installed 'bin/java.exe')){$JdkHome=$installed}
        else{throw '请用-JdkHome指定JDK17安装目录。'}
    }
    $java=Join-Path $JdkHome 'bin/java.exe'
    if(-not (Test-Path -LiteralPath '.local/auth-keys/private.pem') -and -not (Test-Path -LiteralPath '.local/auth-keys/public.pem')) {
        & $java deploy/auth/GenerateAuthKeys.java
        if($LASTEXITCODE -ne 0){throw '生成Auth密钥失败。'}
    }
    foreach($key in @('private.pem','public.pem')) {
        if(-not (Test-Path -LiteralPath ".local/auth-keys/$key")){throw 'Auth密钥不完整，请核对已有密钥；脚本不会覆盖。'}
    }
    foreach($credential in @('order-event','order-inventory','event-inventory','order-payment','payment-order')) {
        $path=".local/service-credentials/$credential.token"
        if(-not (Test-Path -LiteralPath $path)) {
            & $java deploy/auth/GenerateServiceCredential.java $path
            if($LASTEXITCODE -ne 0){throw '生成内部服务凭证失败。'}
        }
    }
    if(-not $SkipPackage) {
        if(-not $MavenCommand) {
            $found=Get-Command mvn.cmd -ErrorAction SilentlyContinue
            if($found){$MavenCommand=$found.Source}
            elseif(Test-Path -LiteralPath 'D:/develop/apache-maven-3.9.14-bin/apache-maven-3.9.14/bin/mvn.cmd') {
                $MavenCommand='D:/develop/apache-maven-3.9.14-bin/apache-maven-3.9.14/bin/mvn.cmd'
            }else{throw '请指定-MavenCommand，或在IDEA执行package后使用-SkipPackage。'}
        }
        $previousJavaHome=$env:JAVA_HOME
        try {
            $env:JAVA_HOME=$JdkHome
            & $MavenCommand '-Dmaven.repo.local=target/.m2' package -DskipTests -q
            if($LASTEXITCODE -ne 0){throw 'Maven打包失败。'}
        } finally { $env:JAVA_HOME=$previousJavaHome }
    }
    foreach($module in @('ticket-auth-service','ticket-event-service','ticket-order-service','ticket-inventory-service','ticket-payment-service','ticket-gateway')) {
        if(-not (Test-Path -LiteralPath "$module/target/$module-1.0-SNAPSHOT.jar")){throw "缺少$module的JAR，请先package。"}
    }
    if(-not $NacosHome){$NacosHome=Join-Path $script:TicketProjectRoot '.local/nacos-3.1.2/nacos'}
    $nacosJar=Join-Path $NacosHome 'target/nacos-server.jar'
    if(-not (Test-Path -LiteralPath $nacosJar)){throw '需要Nacos3.1.2官方发行包；用-NacosHome指定解压后的nacos目录。'}
    $context=Join-Path $script:TicketProjectRoot '.local/docker/nacos'
    New-Item -ItemType Directory -Path $context -Force | Out-Null
    Copy-Item -LiteralPath $nacosJar -Destination (Join-Path $context 'nacos-server.jar') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'nacos/application.properties') -Destination (Join-Path $context 'application.properties') -Force
    [IO.File]::WriteAllText((Join-Path $context '.dockerignore'),"**`n!nacos-server.jar`n!application.properties`n",(New-Object Text.UTF8Encoding($false)))

    # 只下载本机缺失的基础镜像；已有镜像无需访问Docker Hub。
    foreach($image in @('eclipse-temurin:17-jre-jammy','mysql:8.0','redis:7.4-alpine','rabbitmq:4.3.6-management','grafana/tempo:2.9.0','grafana/grafana:13.2.2','prom/prometheus:v3.14.0')) {
        & $script:TicketDocker image inspect $image *> $null
        if($LASTEXITCODE -ne 0) {
            & $script:TicketDocker pull $image
            if($LASTEXITCODE -ne 0){throw "无法下载$image，请检查Docker网络/代理。"}
        }
    }
    Invoke-TicketCompose config --quiet
    Invoke-TicketCompose build
    # 仅处理之前教程的Compose项目，避免18061冲突；不触碰其他项目。
    $existing=& $script:TicketDocker ps -a --filter 'label=com.docker.compose.project=ticket-event-demo' --format '{{.Names}}'
    if($existing) {
        $previousUsername=$env:LOCAL_MYSQL_USERNAME
        try {
            if(-not $env:LOCAL_MYSQL_USERNAME){$env:LOCAL_MYSQL_USERNAME=Read-TicketVariable 'LOCAL_MYSQL_USERNAME'}
            & $script:TicketDocker compose -f compose.event.yml down
            if($LASTEXITCODE -ne 0){throw '旧Event演示容器停止失败。'}
        } finally { $env:LOCAL_MYSQL_USERNAME=$previousUsername }
    }
    Invoke-TicketCompose up -d --scale "order=$OrderReplicas" --wait --wait-timeout 360
    Invoke-TicketCompose ps
    Write-Output '启动完成：Gateway http://localhost:18060；Nacos http://localhost:18080；Grafana http://localhost:3002'
    Write-Output '独立容器MySQL端口13306，Windows原数据库仍保留。管理员docker_admin，密码保存在.local/docker/admin.password，不输出到终端。'
} finally { Pop-Location }
