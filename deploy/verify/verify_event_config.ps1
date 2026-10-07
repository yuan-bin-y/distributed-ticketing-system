param([string]$JdkHome='C:\Users\RE\.jdks\ms-17.0.20',[switch]$CompileOnly)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$work=Join-Path $project '.local/event-config-verification'
$libraries=Join-Path $work 'lib'
$compiled=Join-Path $work 'classes'
New-Item -ItemType Directory -Path $libraries,$compiled -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$artifact=Join-Path $project 'ticket-event-service/target/ticket-event-service-1.0-SNAPSHOT.jar'
$archive=[IO.Compression.ZipFile]::OpenRead($artifact)
try {
    $paths=foreach($entry in ($archive.Entries | Where-Object FullName -Like 'BOOT-INF/lib/*.jar')) {
        $destination=Join-Path $libraries $entry.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$true)
        $destination
    }
} finally { $archive.Dispose() }
$classpath=(@($compiled,(Join-Path $project 'ticket-event-service/target/classes'))+@($paths))-join ';'
& (Join-Path $JdkHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d $compiled (Join-Path $PSScriptRoot 'EventConfigVerification.java')
if($LASTEXITCODE -ne 0){throw 'Configuration verification compile failed.'}
if($CompileOnly){return}
$original=@{}
try {
    foreach($name in @('LOCAL_REDIS_HOST','LOCAL_REDIS_PORT','LOCAL_REDIS_PASSWORD','NACOS_SERVER_ADDR','NACOS_CONFIG_NAMESPACE','NACOS_USERNAME','NACOS_PASSWORD')) {
        $original[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
        if([string]::IsNullOrEmpty($original[$name])) {
            $value=[Environment]::GetEnvironmentVariable($name,'User')
            if(-not [string]::IsNullOrEmpty($value)){[Environment]::SetEnvironmentVariable($name,$value,'Process')}
        }
    }
    & (Join-Path $JdkHome 'bin/java.exe') '-Dnacos.logging.default.config.enabled=false' -cp $classpath EventConfigVerification
    if($LASTEXITCODE -ne 0){throw 'Nacos configuration verification failed.'}
} finally {
    foreach($name in $original.Keys) {
        if($null -eq $original[$name]){Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue}
        else{[Environment]::SetEnvironmentVariable($name,$original[$name],'Process')}
    }
}
