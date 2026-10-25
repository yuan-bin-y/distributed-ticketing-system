param([switch]$Integration,[switch]$Redis,[switch]$RabbitMQ,
      [int]$RedisPort=16379,[int]$RabbitMqPort=15673,
      [string]$MySqlBase=$env:TICKET_TEST_MYSQL_BASE,[string]$JdkHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../docker/common.ps1')
if(-not $JdkHome){throw '请设置JAVA_HOME为JDK17目录，或指定-JdkHome。'}
if($RabbitMQ-and-not$Integration){throw 'RabbitMQ回归使用独立MySQL库，请同时指定-Integration。'}
if($RedisPort-lt1-or$RedisPort-gt65535-or$RabbitMqPort-lt1-or$RabbitMqPort-gt65535){throw '测试端口范围必须为1至65535。'}
$previousJava=$env:JAVA_HOME
$original=@{}
Push-Location $script:TicketProjectRoot
try {
    $env:JAVA_HOME=$JdkHome
    $arguments=@('-B','-pl','ticket-payment-service,ticket-order-service,ticket-inventory-service,ticket-gateway,ticket-event-service','-am','test')
    if($Integration) {
        if($MySqlBase){
            $original['TICKET_TEST_MYSQL_BASE']=$env:TICKET_TEST_MYSQL_BASE
            $env:TICKET_TEST_MYSQL_BASE=$MySqlBase
        }
        foreach($name in @('LOCAL_MYSQL_USERNAME','LOCAL_MYSQL_PASSWORD')) {
            $original[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
            $value=Read-TicketVariable $name
            if(-not $value){throw "请设置Windows环境变量$name。"}
            [Environment]::SetEnvironmentVariable($name,$value,'Process')
        }
        $arguments+='-Dticket.test.mysql=true'
    }
    if($Redis){$arguments+='-Dticket.test.redis=true';$arguments+="-Dticket.test.redis.port=$RedisPort";if($Integration){$arguments+='-Dticket.test.prehold=true'}}
    if($RabbitMQ){$arguments+='-Dticket.test.rabbitmq=true';$arguments+="-Dticket.test.rabbitmq.port=$RabbitMqPort"}
    & mvn.cmd @arguments
    if($LASTEXITCODE -ne 0){throw '自动回归失败；查看各模块target/surefire-reports。'}
} finally {
    $env:JAVA_HOME=$previousJava
    foreach($name in $original.Keys){[Environment]::SetEnvironmentVariable($name,$original[$name],'Process')}
    Pop-Location
}
