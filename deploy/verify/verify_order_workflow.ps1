param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [Alias('JavaHome')][string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [switch]$CompileOnly
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$artifact = Join-Path $ProjectRoot 'ticket-order-service\target\ticket-order-service-1.0-SNAPSHOT.jar'
$inventoryArtifact = Join-Path $ProjectRoot 'ticket-inventory-service\target\ticket-inventory-service-1.0-SNAPSHOT.jar'
foreach ($required in @($artifact, $inventoryArtifact)) {
    if (-not (Test-Path -LiteralPath $required)) { throw 'Package Order and Inventory first.' }
}
$verificationRoot = Join-Path $ProjectRoot '.local\order-workflow-verification'
$libraryRoot = Join-Path $verificationRoot 'lib'
$compiledRoot = Join-Path $verificationRoot 'classes'
New-Item -ItemType Directory -Path $libraryRoot, $compiledRoot -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($artifact)
try {
    $driver = @($archive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/mysql-connector-j-*.jar' })
    if ($driver.Count -ne 1) { throw 'Expected one MySQL connector.' }
    $driverPath = Join-Path $libraryRoot $driver[0].Name
    [IO.Compression.ZipFileExtensions]::ExtractToFile($driver[0], $driverPath, $true)
} finally { $archive.Dispose() }
$classPath = @($compiledRoot, $driverPath) -join ';'
& (Join-Path $JdkHome 'bin\javac.exe') '-encoding' 'UTF-8' '-cp' $classPath '-d' $compiledRoot (Join-Path $PSScriptRoot 'OrderWorkflowVerification.java')
if ($LASTEXITCODE -ne 0) { throw 'Workflow verification compilation failed.' }
if ($CompileOnly) { Write-Output 'Workflow verification compiled.'; return }
$originalEnvironment = @{}
try {
    foreach ($name in @('LOCAL_MYSQL_USERNAME', 'LOCAL_MYSQL_PASSWORD')) {
        $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        $value = $originalEnvironment[$name]
        if ([string]::IsNullOrEmpty($value)) { $value = [Environment]::GetEnvironmentVariable($name, 'User') }
        if ([string]::IsNullOrEmpty($value)) { throw "Set $name in Windows user environment." }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    & (Join-Path $JdkHome 'bin\java.exe') '-cp' $classPath 'OrderWorkflowVerification' $ProjectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Workflow verification failed; review the isolated run logs.' }
} finally {
    foreach ($name in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process')
    }
}
