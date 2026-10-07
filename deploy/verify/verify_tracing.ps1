param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [switch]$Full,
    [int]$RabbitPort = 5679
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
# 共用已有隔离资源夹具；仅复用编译步骤，不运行旧验收入口。
& (Join-Path $PSScriptRoot 'verify_event_administration.ps1') -ProjectRoot $ProjectRoot -JdkHome $JdkHome -CompileOnly
$work = Join-Path $ProjectRoot '.local\event-administration-verification'
$compiled = Join-Path $work 'classes'
$classpath = (@((Join-Path $ProjectRoot 'ticket-auth-service\target\classes'), $compiled) +
    @(Get-ChildItem (Join-Path $work 'lib') -Filter '*.jar' | ForEach-Object FullName)) -join ';'
# Full 模式需要 RabbitMQ Java 客户端，复用已有 MQ 夹具的依赖提取。
if ($Full) {
    & (Join-Path $PSScriptRoot 'verify_payment_mq.ps1') -ProjectRoot $ProjectRoot -JdkHome $JdkHome -CompileOnly
    $rabbitJar = Get-ChildItem (Join-Path $ProjectRoot '.local\order-payment-verification\lib') -Filter 'amqp-client-*.jar' | Select-Object -First 1
    $classpath += ';' + $rabbitJar.FullName
}
$sources = @((Join-Path $PSScriptRoot 'TracingVerification.java'))
if ($Full) { $sources += (Join-Path $PSScriptRoot 'FullTracingVerification.java') }
& (Join-Path $JdkHome 'bin\javac.exe') -encoding UTF-8 -cp $classpath -d $compiled @sources
if ($LASTEXITCODE -ne 0) { throw 'Tracing verifier compilation failed.' }
$saved = @{}
try {
    foreach ($name in @('LOCAL_MYSQL_USERNAME','LOCAL_MYSQL_PASSWORD','LOCAL_REDIS_HOST','LOCAL_REDIS_PORT','LOCAL_REDIS_PASSWORD',
        'TICKET_TRACING_EXPORT_ENABLED','TICKET_TRACING_SAMPLE_RATE',
        'LOCAL_RABBITMQ_HOST','LOCAL_RABBITMQ_PORT','LOCAL_RABBITMQ_USERNAME','LOCAL_RABBITMQ_PASSWORD','LOCAL_RABBITMQ_VHOST')) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        $value = $saved[$name]
        if ([string]::IsNullOrEmpty($value)) { $value = [Environment]::GetEnvironmentVariable($name,'User') }
        if ($name -eq 'TICKET_TRACING_EXPORT_ENABLED') { $value = 'true' }
        if ($name -eq 'TICKET_TRACING_SAMPLE_RATE') { $value = '1.0' }
        if ($Full -and $name -eq 'LOCAL_RABBITMQ_PORT') { $value = [string]$RabbitPort }
        if ([string]::IsNullOrEmpty($value)) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name,$value,'Process') }
    }
    $entry = if ($Full) { 'FullTracingVerification' } else { 'TracingVerification' }
    & (Join-Path $JdkHome 'bin\java.exe') -cp $classpath $entry $ProjectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Tracing verification failed; inspect isolated service logs.' }
} finally {
    foreach ($name in $saved.Keys) {
        if ($null -eq $saved[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    }
}
