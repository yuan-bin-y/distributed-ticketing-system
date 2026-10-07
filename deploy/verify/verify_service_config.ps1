param([string]$JdkHome='C:\Users\RE\.jdks\ms-17.0.20',[switch]$CompileOnly)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$modules=@('ticket-auth-service','ticket-gateway','ticket-event-service','ticket-order-service','ticket-inventory-service','ticket-payment-service')
$work=Join-Path $project '.local/service-config-verification'
New-Item -ItemType Directory -Path $work -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$original=@{}
try {
    foreach($name in @('NACOS_SERVER_ADDR','NACOS_CONFIG_NAMESPACE','NACOS_USERNAME','NACOS_PASSWORD')) {
        $original[$name]=[Environment]::GetEnvironmentVariable($name,'Process')
        if([string]::IsNullOrEmpty($original[$name])) {
            $value=[Environment]::GetEnvironmentVariable($name,'User')
            if(-not [string]::IsNullOrEmpty($value)){[Environment]::SetEnvironmentVariable($name,$value,'Process')}
        }
    }
    foreach($module in $modules) {
        $moduleWork=Join-Path $work $module
        $libraries=Join-Path $moduleWork 'lib'
        $compiled=Join-Path $moduleWork 'classes'
        New-Item -ItemType Directory -Path $libraries,$compiled -Force | Out-Null
        $archive=[IO.Compression.ZipFile]::OpenRead((Join-Path $project "$module/target/$module-1.0-SNAPSHOT.jar"))
        try {
            $paths=foreach($entry in ($archive.Entries | Where-Object FullName -Like 'BOOT-INF/lib/*.jar')) {
                $destination=Join-Path $libraries $entry.Name
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$true)
                $destination
            }
        } finally { $archive.Dispose() }
        # 仅加载当前服务的resources，避免MVC/WebFlux依赖混用或application.yml串台。
        $classpath=(@($compiled,(Join-Path $project "$module/target/classes"))+@($paths))-join ';'
        & (Join-Path $JdkHome 'bin/javac.exe') -encoding UTF-8 -cp $classpath -d $compiled (Join-Path $PSScriptRoot 'ServiceConfigVerification.java')
        if($LASTEXITCODE -ne 0){throw "Verification compile failed: $module"}
        if(-not $CompileOnly) {
            $log=Join-Path $moduleWork 'verification.log'
            & (Join-Path $JdkHome 'bin/java.exe') -cp $classpath ServiceConfigVerification $module *> $log
            if($LASTEXITCODE -ne 0){throw "Configuration verification failed: $module. Inspect $log"}
            Get-Content -LiteralPath $log | Where-Object { $_ -like 'PASS *' }
        }
    }
} finally {
    foreach($name in $original.Keys) {
        if($null -eq $original[$name]){Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue}
        else{[Environment]::SetEnvironmentVariable($name,$original[$name],'Process')}
    }
}
