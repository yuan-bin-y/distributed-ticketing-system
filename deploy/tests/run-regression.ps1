param([switch]$Integration,[string]$JdkHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../docker/common.ps1')
if(-not $JdkHome){throw '请设置JAVA_HOME为JDK17目录，或指定-JdkHome。'}
$previousJava=$env:JAVA_HOME
$original=@{}
Push-Location $script:TicketProjectRoot
try {
    $env:JAVA_HOME=$JdkHome
    $arguments=@('-B','-pl','ticket-payment-service,ticket-order-service,ticket-inventory-service,ticket-gateway,ticket-event-service','-am','test')
    if($Integration) {
        foreach($name in @('LOCAL_MYSQL_USERNAME','LOCAL_MYSQL_PASSWORD')) {
            $original[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
            $value=Read-TicketVariable $name
            if(-not $value){throw "请设置Windows环境变量$name。"}
            [Environment]::SetEnvironmentVariable($name,$value,'Process')
        }
        $arguments+='-Dticket.test.mysql=true'
    }
    & mvn.cmd @arguments
    if($LASTEXITCODE -ne 0){throw '自动回归失败；查看各模块target/surefire-reports。'}
} finally {
    $env:JAVA_HOME=$previousJava
    foreach($name in $original.Keys){[Environment]::SetEnvironmentVariable($name,$original[$name],'Process')}
    Pop-Location
}
