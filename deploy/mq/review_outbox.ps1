param([string]$EventId,[string]$Operator,[string]$Reason,[string]$JdkHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if(-not $JdkHome){throw '请设置JAVA_HOME为JDK 17目录。'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$helper=Join-Path $root '.local/outbox-review'
New-Item -ItemType Directory -Force -Path $helper | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $root 'ticket-payment-service/target/ticket-payment-service-1.0-SNAPSHOT.jar'))
try {
    foreach($entry in $archive.Entries | Where-Object {$_.FullName -like 'BOOT-INF/lib/mysql-connector-j-*.jar'}) {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $helper $entry.Name),$true)
    }
} finally {$archive.Dispose()}
& (Join-Path $JdkHome 'bin/javac.exe') -encoding UTF-8 -d $helper (Join-Path $PSScriptRoot 'OutboxReview.java')
if($LASTEXITCODE -ne 0){throw 'Outbox操作工具编译失败。'}
$arguments=@('-cp',"$helper;$helper/*",'OutboxReview')
if($EventId) {
    if(-not $Operator -or -not $Reason){throw '重放必须提供Operator和Reason。'}
    $arguments+=@('--replay',$EventId,$Operator,$Reason)
}
& (Join-Path $JdkHome 'bin/java.exe') @arguments
if($LASTEXITCODE -ne 0){throw 'Outbox操作失败；失败事务不会改变事件状态或留下重放记录。'}
