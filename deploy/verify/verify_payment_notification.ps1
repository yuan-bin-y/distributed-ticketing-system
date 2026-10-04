param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [Alias('JavaHome')][string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [switch]$CompileOnly
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$artifact = Join-Path $ProjectRoot 'ticket-order-service\target\ticket-order-service-1.0-SNAPSHOT.jar'
$paymentArtifact = Join-Path $ProjectRoot 'ticket-payment-service\target\ticket-payment-service-1.0-SNAPSHOT.jar'
foreach ($required in @($artifact,$paymentArtifact)) {
    if (-not (Test-Path -LiteralPath $required)) { throw 'Package Order and Payment first.' }
}
$verificationRoot = Join-Path $ProjectRoot '.local\order-payment-verification'
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
& (Join-Path $JdkHome 'bin\javac.exe') '-encoding' 'UTF-8' '-cp' $classPath '-d' $compiledRoot (Join-Path $PSScriptRoot 'OrderPaymentVerification.java') (Join-Path $PSScriptRoot 'PaymentNotificationVerification.java')
if ($LASTEXITCODE -ne 0) { throw 'Payment notification verification compilation failed.' }
if ($CompileOnly) { Write-Output 'Payment notification verification compiled; no database opened.'; return }
$originalEnvironment = @{}
try {
    foreach ($name in @('LOCAL_MYSQL_USERNAME','LOCAL_MYSQL_PASSWORD')) {
        $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        $value = $originalEnvironment[$name]
        if ([string]::IsNullOrEmpty($value)) { $value = [Environment]::GetEnvironmentVariable($name,'User') }
        if ([string]::IsNullOrEmpty($value)) { throw "Set $name in Windows user environment." }
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
    & (Join-Path $JdkHome 'bin\java.exe') '-cp' $classPath 'PaymentNotificationVerification' $ProjectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Payment notification verification failed; inspect the isolated run logs.' }
} finally {
    foreach ($name in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name,$originalEnvironment[$name],'Process')
    }
}
