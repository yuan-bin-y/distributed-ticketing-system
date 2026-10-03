param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [Alias('JavaHome')]
    [string]$JdkHome = 'C:\Users\RE\.jdks\ms-17.0.20',
    [ValidateRange(1024, 65535)][int]$OrderPort = 19062,
    [ValidateRange(1024, 65535)][int]$InventoryPort = 19063,
    [string]$NacosAddress = '127.0.0.1:8848',
    [string]$NacosGroup,
    [switch]$CompileOnly
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$orderArtifact = Join-Path $ProjectRoot 'ticket-order-service\target\ticket-order-service-1.0-SNAPSHOT.jar'
$inventoryArtifact = Join-Path $ProjectRoot 'ticket-inventory-service\target\ticket-inventory-service-1.0-SNAPSHOT.jar'
$sourcePath = Join-Path $PSScriptRoot 'OrderInventoryChainVerification.java'
$javacPath = Join-Path $JdkHome 'bin\javac.exe'
$javaPath = Join-Path $JdkHome 'bin\java.exe'
foreach ($requiredPath in @($orderArtifact, $inventoryArtifact, $sourcePath, $javacPath, $javaPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required file missing: $requiredPath. Package the Order and Inventory services first."
    }
}

$runId = [Guid]::NewGuid().ToString('N')
if ([string]::IsNullOrWhiteSpace($NacosGroup)) {
    $NacosGroup = 'TICKET_ORDER_INVENTORY_VERIFY_' + $runId.Substring(0, 8)
}
if ($NacosGroup -notmatch '^[A-Za-z0-9_-]+$') { throw 'NacosGroup may contain only letters, digits, underscores or hyphens.' }
if ($OrderPort -eq $InventoryPort) { throw 'Order and Inventory ports must differ.' }

$verificationPath = Join-Path $ProjectRoot '.local\order-inventory-chain-verification'
$librariesPath = Join-Path $verificationPath 'lib'
$compiledPath = Join-Path $verificationPath 'classes'
$runPath = Join-Path $verificationPath $runId
New-Item -ItemType Directory -Path $librariesPath, $compiledPath, $runPath -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($inventoryArtifact)
try {
    $driverEntries = @($archive.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/mysql-connector-j-*.jar' })
    if ($driverEntries.Count -ne 1) { throw 'Expected one MySQL connector in the executable Inventory JAR.' }
    $driverPath = Join-Path $librariesPath $driverEntries[0].Name
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($driverEntries[0], $driverPath, $true)
} finally {
    $archive.Dispose()
}
$classPath = @($compiledPath, $driverPath) -join ';'
& $javacPath '-encoding' 'UTF-8' '-cp' $classPath '-d' $compiledPath $sourcePath
if ($LASTEXITCODE -ne 0) { throw "Chain verification compilation failed (exit $LASTEXITCODE)." }
if ($CompileOnly) {
    Write-Host 'Chain verification compilation passed; no processes started or database connections opened.'
    return
}

function Assert-PortFree([int]$Port) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try {
        $listener.Server.ExclusiveAddressUse = $true
        $listener.Start()
    } catch {
        throw "Verification port $Port is unavailable. Choose another port; existing processes will not be stopped."
    } finally {
        $listener.Stop()
    }
}

function Get-HealthyInstances([string]$ServiceName) {
    $service = [Uri]::EscapeDataString($ServiceName)
    $group = [Uri]::EscapeDataString($NacosGroup)
    $url = "http://$NacosAddress/nacos/v1/ns/instance/list?serviceName=$service&groupName=$group&healthyOnly=true"
    $result = Invoke-RestMethod -Uri $url -TimeoutSec 3
    return @($result.hosts)
}

function Start-VerificationService([string]$Artifact, [int]$Port, [string]$LogName) {
    $arguments = @('-jar', ('"' + $Artifact + '"'), "--server.port=$Port",
        "--spring.cloud.nacos.discovery.server-addr=$NacosAddress",
        "--spring.cloud.nacos.discovery.group=$NacosGroup",
        '--spring.cloud.nacos.discovery.ip=127.0.0.1', '--spring.cloud.nacos.discovery.namespace=',
        '--spring.cloud.nacos.discovery.enabled=true', '--spring.cloud.discovery.enabled=true',
        '--spring.cloud.loadbalancer.retry.enabled=false', '--spring.main.banner-mode=off', '--logging.level.root=WARN')
    return Start-Process -FilePath $javaPath -ArgumentList $arguments -WorkingDirectory $ProjectRoot -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runPath "$LogName.out.log") `
        -RedirectStandardError (Join-Path $runPath "$LogName.err.log") -PassThru
}

function Wait-HealthyRegistration([System.Diagnostics.Process]$Process, [string]$ServiceName, [int]$Port) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) { throw "$ServiceName verification process exited. Review logs in $runPath." }
        try {
            $instances = @(Get-HealthyInstances $ServiceName)
            $registered = @($instances | Where-Object { $_.ip -eq '127.0.0.1' -and $_.port -eq $Port -and $_.healthy })
            if ($instances.Count -eq 1 -and $registered.Count -eq 1) { return }
            if ($instances.Count -gt 1) { throw 'Verification Nacos group contains unexpected instances.' }
        } catch {
            # Server or client registration may still be starting; retry within the bounded deadline.
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$ServiceName did not register a healthy instance. Review logs in $runPath."
}

Assert-PortFree $OrderPort
Assert-PortFree $InventoryPort
foreach ($serviceName in @('ticket-order-service', 'ticket-inventory-service')) {
    if (@(Get-HealthyInstances $serviceName).Count -gt 0) {
        throw "Nacos group $NacosGroup is already in use. Choose an unused group."
    }
}

$originalEnvironment = @{}
$ownedProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
try {
    # Read credentials only into this process and inherited child environments, never arguments or files.
    foreach ($variableName in @('LOCAL_MYSQL_USERNAME', 'LOCAL_MYSQL_PASSWORD')) {
        $originalEnvironment[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
        $variableValue = $originalEnvironment[$variableName]
        if ([string]::IsNullOrEmpty($variableValue)) {
            $variableValue = [Environment]::GetEnvironmentVariable($variableName, 'User')
        }
        if ([string]::IsNullOrEmpty($variableValue)) { throw "Set $variableName in the process or Windows user environment." }
        [Environment]::SetEnvironmentVariable($variableName, $variableValue, 'Process')
    }
    Write-Host 'Starting isolated Order and Inventory verification processes; Nacos and MySQL must already be running.'
    $inventoryProcess = Start-VerificationService $inventoryArtifact $InventoryPort 'inventory'
    $ownedProcesses.Add($inventoryProcess)
    Wait-HealthyRegistration $inventoryProcess 'ticket-inventory-service' $InventoryPort
    $orderProcess = Start-VerificationService $orderArtifact $OrderPort 'order'
    $ownedProcesses.Add($orderProcess)
    Wait-HealthyRegistration $orderProcess 'ticket-order-service' $OrderPort
    Write-Host 'Healthy registrations verified. Running real HTTP and database checks with generated fixture rows.'
    & $javaPath '-cp' $classPath 'OrderInventoryChainVerification' "http://127.0.0.1:$OrderPort" "http://127.0.0.1:$InventoryPort"
    if ($LASTEXITCODE -ne 0) { throw "Order -> Inventory chain verification failed (exit $LASTEXITCODE)." }
} finally {
    foreach ($ownedProcess in $ownedProcesses) {
        $ownedProcess.Refresh()
        if (-not $ownedProcess.HasExited) {
            $ownedProcess.Kill()
            $ownedProcess.WaitForExit()
        }
        $ownedProcess.Dispose()
    }
    foreach ($variableName in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($variableName, $originalEnvironment[$variableName], 'Process')
    }
}
