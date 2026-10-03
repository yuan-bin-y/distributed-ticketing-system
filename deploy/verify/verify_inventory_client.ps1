param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [Alias('JavaHome')]
    [string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [switch]$CompileOnly
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$artifactPath = Join-Path $ProjectRoot 'ticket-order-service\target\ticket-order-service-1.0-SNAPSHOT.jar'
$classesPath = Join-Path $ProjectRoot 'ticket-order-service\target\classes'
$sourcePath = Join-Path $PSScriptRoot 'InventoryClientVerification.java'
$javacPath = Join-Path $JdkHome 'bin\javac.exe'
$javaPath = Join-Path $JdkHome 'bin\java.exe'

foreach ($requiredPath in @($artifactPath, $classesPath, $sourcePath, $javacPath, $javaPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required file or directory missing: $requiredPath. Build ticket-order-service and its dependent modules first."
    }
}

$verificationPath = Join-Path $ProjectRoot '.local\inventory-client-verification'
$librariesPath = Join-Path $verificationPath 'lib'
$compiledPath = Join-Path $verificationPath 'classes'
New-Item -ItemType Directory -Path $librariesPath, $compiledPath -Force | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    $libraryEntries = @($archive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*.jar' })
    if ($libraryEntries.Count -eq 0) {
        throw 'Order artifact is not a Spring Boot executable JAR. Package the order module first.'
    }
    # Compile and run using only the exact artifact dependencies, including ticket-common.
    $libraryPaths = foreach ($entry in $libraryEntries) {
        $destinationPath = Join-Path $librariesPath $entry.Name
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destinationPath, $true)
        $destinationPath
    }
} finally {
    $archive.Dispose()
}

$classPath = (@($classesPath, $compiledPath) + @($libraryPaths)) -join ';'
& $javacPath '-encoding' 'UTF-8' '-cp' $classPath '-d' $compiledPath $sourcePath
if ($LASTEXITCODE -ne 0) { throw "Inventory client verification compilation failed (exit $LASTEXITCODE)." }
if ($CompileOnly) {
    Write-Host 'Inventory client verification compilation passed; no server was started.'
    return
}

Write-Host 'Running inventory HTTP client verification with local stubs (no MySQL or Nacos required).'
& $javaPath '-cp' $classPath 'InventoryClientVerification'
if ($LASTEXITCODE -ne 0) { throw "Inventory client verification failed (exit $LASTEXITCODE)." }
