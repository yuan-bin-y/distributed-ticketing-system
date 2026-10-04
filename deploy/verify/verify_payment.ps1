param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [Alias('JavaHome')][string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [switch]$CompileOnly,
    [switch]$VerifyNacos
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$artifact = Join-Path $ProjectRoot 'ticket-payment-service\target\ticket-payment-service-1.0-SNAPSHOT.jar'
$classes = Join-Path $ProjectRoot 'ticket-payment-service\target\classes'
$source = Join-Path $PSScriptRoot 'PaymentVerification.java'
$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
foreach ($required in @($artifact,$classes,$source,$javac,$java)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required path missing: $required" }
}
$verificationRoot = Join-Path $ProjectRoot '.local\payment-verification'
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
$classPath = (@($classes,$compiledRoot) + @($libraryPaths)) -join ';'
& $javac '-encoding' 'UTF-8' '-cp' $classPath '-d' $compiledRoot $source
if ($LASTEXITCODE -ne 0) { throw 'Payment verification compilation failed.' }
if ($CompileOnly) { Write-Output 'Payment verification compiled; no database opened.'; return }
$originalEnvironment = @{}
try {
    foreach ($name in @('LOCAL_MYSQL_USERNAME','LOCAL_MYSQL_PASSWORD')) {
        $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        $value = $originalEnvironment[$name]
        if ([string]::IsNullOrEmpty($value)) { $value = [Environment]::GetEnvironmentVariable($name,'User') }
        if ([string]::IsNullOrEmpty($value)) { throw "Set $name in Windows user environment." }
        [Environment]::SetEnvironmentVariable($name,$value,'Process')
    }
    $verificationArgs = @('-cp',$classPath,'PaymentVerification')
    if ($VerifyNacos) { $verificationArgs += '--verify-nacos' }
    & $java @verificationArgs
    if ($LASTEXITCODE -ne 0) { throw 'Payment verification failed.' }
} finally {
    foreach ($name in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name,$originalEnvironment[$name],'Process')
    }
}
