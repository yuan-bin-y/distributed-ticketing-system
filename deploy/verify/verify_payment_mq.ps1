param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [Alias('JavaHome')][string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [switch]$CompileOnly,
    [switch]$Tracing
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$artifact = Join-Path $ProjectRoot 'ticket-order-service\target\ticket-order-service-1.0-SNAPSHOT.jar'
$paymentArtifact = Join-Path $ProjectRoot 'ticket-payment-service\target\ticket-payment-service-1.0-SNAPSHOT.jar'
$inventoryArtifact = Join-Path $ProjectRoot 'ticket-inventory-service\target\ticket-inventory-service-1.0-SNAPSHOT.jar'
foreach ($required in @($artifact,$paymentArtifact,$inventoryArtifact)) {
    if (-not (Test-Path -LiteralPath $required)) { throw 'Package Order, Inventory and Payment first.' }
}
$verificationRoot = Join-Path $ProjectRoot $(if ($Tracing) { '.local\mq-tracing-verification' } else { '.local\order-payment-verification' })
$libraryRoot = Join-Path $verificationRoot 'lib'
$compiledRoot = Join-Path $verificationRoot 'classes'
New-Item -ItemType Directory -Path $libraryRoot,$compiledRoot -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($artifact)
try {
    $libraryPaths = foreach ($entry in ($archive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*.jar' })) {
        $destination = Join-Path $libraryRoot $entry.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$true)
        $destination
    }
} finally { $archive.Dispose() }
$classPath = (@($compiledRoot) + @($libraryPaths)) -join ';'
& (Join-Path $JdkHome 'bin\javac.exe') '-encoding' 'UTF-8' '-cp' $classPath '-d' $compiledRoot (Join-Path $PSScriptRoot 'OrderPaymentVerification.java') (Join-Path $PSScriptRoot 'PaymentFulfillmentVerification.java') (Join-Path $PSScriptRoot 'PaymentMqVerification.java') (Join-Path $PSScriptRoot 'PaymentMqTracingVerification.java') (Join-Path $ProjectRoot 'deploy/mq/PaymentDeadLetterReplay.java')
if ($LASTEXITCODE -ne 0) { throw 'Payment MQ verification compilation failed.' }
if ($CompileOnly) { Write-Output 'Payment MQ verification compiled; no database opened.'; return }
$originalEnvironment = @{}
try {
    foreach ($name in @('LOCAL_MYSQL_USERNAME','LOCAL_MYSQL_PASSWORD',
            'LOCAL_RABBITMQ_HOST','LOCAL_RABBITMQ_PORT','LOCAL_RABBITMQ_USERNAME',
            'LOCAL_RABBITMQ_PASSWORD','LOCAL_RABBITMQ_VHOST',
            'TICKET_TRACING_EXPORT_ENABLED','TICKET_TRACING_SAMPLE_RATE')) {
        $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        $value = $originalEnvironment[$name]
        if ([string]::IsNullOrEmpty($value)) { $value = [Environment]::GetEnvironmentVariable($name,'User') }
        if ($Tracing -and $name -eq 'TICKET_TRACING_EXPORT_ENABLED') { $value='true' }
        if ($Tracing -and $name -eq 'TICKET_TRACING_SAMPLE_RATE') { $value='1.0' }
        if ([string]::IsNullOrEmpty($value)) {
            if ($name -like 'LOCAL_MYSQL_*') { throw "Set $name in Windows user environment." }
            continue
        }
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
    $entry = if ($Tracing) { 'PaymentMqTracingVerification' } else { 'PaymentMqVerification' }
    & (Join-Path $JdkHome 'bin\java.exe') '-cp' $classPath $entry $ProjectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Payment MQ verification failed; inspect the isolated run logs.' }
} finally {
    foreach ($name in $originalEnvironment.Keys) {
        if ($null -eq $originalEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name,$originalEnvironment[$name],'Process') }
    }
}
