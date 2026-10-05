param([Parameter(Mandatory)][string]$RunDirectory)
$ErrorActionPreference='Stop'
$RunDirectory=[IO.Path]::GetFullPath($RunDirectory)
$windows=@(Import-Csv (Join-Path $RunDirectory 'phase-windows.csv'))
$series=@{}; $pools=[Collections.Generic.List[object]]::new()
function Add-Timing([string]$phase,[string]$scope,[string]$step,[double]$ms){
    $key="$phase|$scope|$step"
    if(-not $series.ContainsKey($key)){$series[$key]=[Collections.Generic.List[double]]::new()}
    $series[$key].Add($ms)
}
foreach($file in (Get-ChildItem -LiteralPath $RunDirectory -Filter '*.log')){
    $service=if($file.Name -like '*inventory*'){'inventory'}elseif($file.Name -like '*order*'){'order'}else{continue}
    foreach($line in [IO.File]::ReadLines($file.FullName)){
        $stamp=0L; $scope=''; $fields=@{}
        if($line -match 'PERF epoch_ms=(\d+) scope=(\S+) trace=\S+ outcome=\S+ (.*)$'){
            $stamp=[long]$Matches[1];$scope=$Matches[2];$details=$Matches[3]
            foreach($match in [regex]::Matches($details,'([a-zA-Z0-9_.]+)_us=(\d+)')){$fields[$match.Groups[1].Value]=[double]$match.Groups[2].Value/1000}
        }elseif($line -match '^(\S+) .*POOL active=(\d+) idle=(\d+) pending=(\d+) total=(\d+)'){
            $stamp=[DateTimeOffset]::Parse($Matches[1]).ToUnixTimeMilliseconds()
            $active=[int]$Matches[2];$pending=[int]$Matches[4];$total=[int]$Matches[5]
        }else{continue}
        $window=$windows | Where-Object { $stamp -ge [long]$_.startEpochMs -and $stamp -le [long]$_.endEpochMs } | Select-Object -First 1
        if(-not $window){continue}
        if($scope){
            foreach($step in $fields.Keys){Add-Timing $window.phase $scope $step $fields[$step]}
            $sqlTotal=0.0;foreach($step in $fields.Keys){if($step -like 'sql.*'){$sqlTotal+=$fields[$step]}}
            if($scope -eq 'order.create' -and $fields.ContainsKey('local.transaction')){Add-Timing $window.phase $scope 'transaction.other' ($fields['local.transaction']-$sqlTotal)}
            if($scope -eq 'inventory.reserve.transaction' -and $fields.ContainsKey('transaction')){Add-Timing $window.phase $scope 'transaction.other' ($fields['transaction']-$sqlTotal)}
        }else{$pools.Add([pscustomobject]@{phase=$window.phase;service=$service;active=$active;pending=$pending;total=$total})}
    }
}
$timings=foreach($key in ($series.Keys | Sort-Object)){
    $parts=$key.Split('|');$values=@($series[$key] | Sort-Object);$count=$values.Count
    [pscustomobject]@{phase=$parts[0];scope=$parts[1];step=$parts[2];samples=$count;meanMs=[Math]::Round(($values | Measure-Object -Average).Average,3);p95Ms=[Math]::Round($values[[Math]::Max(0,[Math]::Ceiling($count*.95)-1)],3)}
}
$poolSummary=foreach($group in ($pools | Group-Object phase,service)){
    $rows=$group.Group
    [pscustomobject]@{phase=$rows[0].phase;service=$rows[0].service;samples=$rows.Count;maxActive=($rows | Measure-Object active -Maximum).Maximum;maxPending=($rows | Measure-Object pending -Maximum).Maximum;meanPending=[Math]::Round(($rows | Measure-Object pending -Average).Average,2);maxTotal=($rows | Measure-Object total -Maximum).Maximum}
}
$database=foreach($group in (Import-Csv (Join-Path $RunDirectory 'database-waits.csv') | Group-Object phase)){
    $rows=@($group.Group | Where-Object { -not $_.error })
    [pscustomobject]@{phase=$group.Name;samples=$rows.Count;errors=@($group.Group | Where-Object error).Count;maxOrderWaitEdges=($rows | Measure-Object orderWaitEdges -Maximum).Maximum;maxInventoryWaitEdges=($rows | Measure-Object inventoryWaitEdges -Maximum).Maximum;globalRowLockWaitDelta=if($rows.Count){[long]$rows[-1].globalRowLockWaits-[long]$rows[0].globalRowLockWaits}else{0};globalRowLockTimeDeltaMs=if($rows.Count){[long]$rows[-1].globalRowLockTimeMs-[long]$rows[0].globalRowLockTimeMs}else{0}}
}
$timings | Export-Csv (Join-Path $RunDirectory 'timings.csv') -NoTypeInformation
@{timings=@($timings);pools=@($poolSummary);database=@($database);notes='Nested timers must not be added together. transaction.other includes connection acquisition, begin/commit and uninstrumented work; it is not a direct pool-wait timer. Global counters can include other database traffic.'} | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $RunDirectory 'profiling.json')
$poolSummary | Format-Table -AutoSize
$database | Format-Table -AutoSize
