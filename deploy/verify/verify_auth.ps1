param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [switch]$CompileOnly
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$artifact = Join-Path $ProjectRoot 'ticket-auth-service\target\ticket-auth-service-1.0-SNAPSHOT.jar'
$work = Join-Path $ProjectRoot '.local\auth-verification'
$libraries = Join-Path $work 'lib'
$compiled = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $libraries,$compiled -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($artifact)
try {
    $paths = foreach ($entry in ($archive.Entries | Where-Object FullName -Like 'BOOT-INF/lib/*.jar')) {
        $path = Join-Path $libraries $entry.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$path,$true)
        $path
    }
} finally { $archive.Dispose() }
$classPath = (@((Join-Path $ProjectRoot 'ticket-auth-service\target\classes'),$compiled) + @($paths)) -join ';'
& (Join-Path $JdkHome 'bin\javac.exe') '-encoding' 'UTF-8' '-cp' $classPath '-d' $compiled (Join-Path $PSScriptRoot 'AuthVerification.java')
if ($LASTEXITCODE -ne 0) { throw 'Auth verification compilation failed.' }
if ($CompileOnly) { return }
$original = @{}
try {
    foreach ($name in @('LOCAL_MYSQL_USERNAME','LOCAL_MYSQL_PASSWORD','LOCAL_REDIS_HOST','LOCAL_REDIS_PORT','LOCAL_REDIS_PASSWORD')) {
        $original[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        $value = $original[$name]
        if ([string]::IsNullOrEmpty($value)) { $value = [Environment]::GetEnvironmentVariable($name,'User') }
        if ($name -Like 'LOCAL_MYSQL_*' -and [string]::IsNullOrEmpty($value)) { throw "Set $name first." }
        if (-not [string]::IsNullOrEmpty($value)) {
            [Environment]::SetEnvironmentVariable($name,$value,'Process')
        } else {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        }
    }
    & (Join-Path $JdkHome 'bin\java.exe') '-cp' $classPath 'AuthVerification' $ProjectRoot
    if ($LASTEXITCODE -ne 0) { throw 'Auth verification failed.' }
} finally {
    foreach ($name in $original.Keys) { [Environment]::SetEnvironmentVariable($name,$original[$name],'Process') }
}
