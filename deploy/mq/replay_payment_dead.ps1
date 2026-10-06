param([switch]$Replay,
    [string]$JdkHome='C:\Users\RE\.jdks\ms-17.0.20')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$artifact=Join-Path $projectRoot 'ticket-order-service/target/ticket-order-service-1.0-SNAPSHOT.jar'
$helperRoot=Join-Path $projectRoot '.local/payment-dead-replay'
New-Item -ItemType Directory -Path $helperRoot -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($artifact)
try {
    $libraries=foreach($entry in $archive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*.jar' }) {
        $destination=Join-Path $helperRoot $entry.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$true)
        $destination
    }
} finally { $archive.Dispose() }
$classPath=(@($helperRoot)+@($libraries))-join ';'
& (Join-Path $JdkHome 'bin/javac.exe') '-encoding' 'UTF-8' '-cp' $classPath '-d' $helperRoot (Join-Path $PSScriptRoot 'PaymentDeadLetterReplay.java')
if($LASTEXITCODE -ne 0){throw 'Dead-letter helper compilation failed.'}
$originalEnvironment=@{}
try {
    foreach($name in @('LOCAL_RABBITMQ_HOST','LOCAL_RABBITMQ_PORT','LOCAL_RABBITMQ_USERNAME','LOCAL_RABBITMQ_PASSWORD','LOCAL_RABBITMQ_VHOST','TICKET_MQ_PREFIX')){
        $originalEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
        if([string]::IsNullOrEmpty($originalEnvironment[$name])){
            $value=[Environment]::GetEnvironmentVariable($name,'User')
            if(-not [string]::IsNullOrEmpty($value)){[Environment]::SetEnvironmentVariable($name,$value,'Process')}
        }
    }
    $arguments=@('-cp',$classPath,'PaymentDeadLetterReplay')
    if($Replay){$arguments+='--replay'}
    & (Join-Path $JdkHome 'bin/java.exe') @arguments
    if($LASTEXITCODE -ne 0){throw 'Dead-letter replay failed; original delivery is retained unless publish was confirmed.'}
} finally {
    foreach($name in $originalEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$originalEnvironment[$name],'Process')}
}
