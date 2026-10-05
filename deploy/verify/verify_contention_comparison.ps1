param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [int]$RequestsPerPhase = 1000,
    [switch]$CompileOnly
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
if (-not $CompileOnly) {
    foreach ($port in @(8848,9848)) {
        $probe = [Net.Sockets.TcpClient]::new()
        try {
            $pending = $probe.ConnectAsync('127.0.0.1',$port)
            if (-not $pending.Wait(3000) -or -not $probe.Connected) { throw "Start local Nacos first; port $port is unavailable." }
        } finally { $probe.Dispose() }
    }
}
$authArtifact = Join-Path $ProjectRoot 'ticket-auth-service\target\ticket-auth-service-1.0-SNAPSHOT.jar'
$orderArtifact = Join-Path $ProjectRoot 'ticket-order-service\target\ticket-order-service-1.0-SNAPSHOT.jar'
foreach ($file in @($authArtifact,$orderArtifact,
    (Join-Path $ProjectRoot 'ticket-gateway\target\ticket-gateway-1.0-SNAPSHOT.jar'),
    (Join-Path $ProjectRoot 'ticket-event-service\target\ticket-event-service-1.0-SNAPSHOT.jar'),
    (Join-Path $ProjectRoot 'ticket-inventory-service\target\ticket-inventory-service-1.0-SNAPSHOT.jar'),
    (Join-Path $ProjectRoot 'ticket-payment-service\target\ticket-payment-service-1.0-SNAPSHOT.jar'))) {
    if (-not (Test-Path -LiteralPath $file)) { throw 'Package Auth, Gateway, Order, Inventory and Payment first.' }
}
$work = Join-Path $ProjectRoot '.local\contention-comparison'
$libraries = Join-Path $work 'lib'
$compiled = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $libraries,$compiled -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($authArtifact)
try {
    $paths = foreach ($entry in ($archive.Entries | Where-Object FullName -Like 'BOOT-INF/lib/*.jar')) {
        $path = Join-Path $libraries $entry.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$path,$true)
        $path
    }
} finally { $archive.Dispose() }
$classPath = (@((Join-Path $ProjectRoot 'ticket-auth-service\target\classes'),$compiled) + @($paths)) -join ';'
& (Join-Path $JdkHome 'bin\javac.exe') '-encoding' 'UTF-8' '-cp' $classPath '-d' $compiled (Join-Path $PSScriptRoot 'AuthVerification.java') (Join-Path $PSScriptRoot 'OrderAuthVerification.java') (Join-Path $PSScriptRoot 'EventAdministrationVerification.java') (Join-Path $PSScriptRoot 'V1AcceptanceVerification.java') (Join-Path $PSScriptRoot 'LoadVerification.java') (Join-Path $PSScriptRoot 'ProfilingLoadVerification.java') (Join-Path $PSScriptRoot 'ContentionComparisonVerification.java')
if ($LASTEXITCODE -ne 0) { throw 'Contention comparison compilation failed.' }
if ($CompileOnly) { return }
$original = @{}
$priorPerf = [Environment]::GetEnvironmentVariable('TICKET_PERF_ENABLED','Process')
[Environment]::SetEnvironmentVariable('TICKET_PERF_ENABLED','true','Process')
try {
    foreach ($name in @('LOCAL_MYSQL_USERNAME','LOCAL_MYSQL_PASSWORD','LOCAL_REDIS_HOST','LOCAL_REDIS_PORT','LOCAL_REDIS_PASSWORD')) {
        $original[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        $value = $original[$name]
        if ([string]::IsNullOrEmpty($value)) { $value = [Environment]::GetEnvironmentVariable($name,'User') }
        if ($name -Like 'LOCAL_MYSQL_*' -and [string]::IsNullOrEmpty($value)) { throw "Set $name first." }
        if (-not [string]::IsNullOrEmpty($value)) { [Environment]::SetEnvironmentVariable($name,$value,'Process') }
        else { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
    }
    & (Join-Path $JdkHome 'bin\java.exe') '-Xms128m' '-Xmx512m' '-cp' $classPath 'ContentionComparisonVerification' $ProjectRoot $RequestsPerPhase
    if ($LASTEXITCODE -ne 0) { throw 'Contention comparison failed; inspect isolated logs.' }
} finally {
    [Environment]::SetEnvironmentVariable('TICKET_PERF_ENABLED',$priorPerf,'Process')
    foreach ($name in $original.Keys) {
        if ($null -eq $original[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name,$original[$name],'Process') }
    }
}


