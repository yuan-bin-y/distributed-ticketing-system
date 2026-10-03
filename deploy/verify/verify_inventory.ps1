param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [Alias('JavaHome')]
    [string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [switch]$CompileOnly
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$artifactPath = Join-Path $ProjectRoot 'ticket-inventory-service\target\ticket-inventory-service-1.0-SNAPSHOT.jar'
$classesPath = Join-Path $ProjectRoot 'ticket-inventory-service\target\classes'
$sourcePath = Join-Path $PSScriptRoot 'InventoryVerification.java'
$javacPath = Join-Path $JdkHome 'bin\javac.exe'
$javaPath = Join-Path $JdkHome 'bin\java.exe'

foreach ($requiredPath in @($artifactPath, $classesPath, $sourcePath, $javacPath, $javaPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required file or directory missing: $requiredPath. Build ticket-inventory-service and its dependent modules first."
    }
}

$verificationPath = Join-Path $ProjectRoot '.local\inventory-verification'
$librariesPath = Join-Path $verificationPath 'lib'
$compiledPath = Join-Path $verificationPath 'classes'
New-Item -ItemType Directory -Path $librariesPath, $compiledPath -Force | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($artifactPath)
try {
    $libraryEntries = @($archive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*.jar' })
    if ($libraryEntries.Count -eq 0) {
        throw 'Inventory artifact is not a Spring Boot executable JAR. Package the inventory module first.'
    }
    # Use only the dependencies listed in this exact artifact, including ticket-common.
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
if ($LASTEXITCODE -ne 0) { throw "Inventory verification compilation failed (exit $LASTEXITCODE)." }
if ($CompileOnly) {
    Write-Host 'Inventory verification compilation passed; no database connection was opened.'
    return
}

# Keep credentials in this process; do not write them into files or console output.
foreach ($variableName in @('LOCAL_MYSQL_USERNAME', 'LOCAL_MYSQL_PASSWORD')) {
    $variableValue = [Environment]::GetEnvironmentVariable($variableName, 'Process')
    if ([string]::IsNullOrEmpty($variableValue)) {
        $variableValue = [Environment]::GetEnvironmentVariable($variableName, 'User')
        if ([string]::IsNullOrEmpty($variableValue)) {
            throw "Set $variableName in the current process or Windows user environment first."
        }
        [Environment]::SetEnvironmentVariable($variableName, $variableValue, 'Process')
    }
}

Write-Host 'Running real MySQL inventory verification (temporary fixture rows will be removed).'
& $javaPath '-cp' $classPath 'InventoryVerification'
if ($LASTEXITCODE -ne 0) { throw "Inventory verification failed (exit $LASTEXITCODE)." }
