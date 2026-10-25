$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$required = @('InventoryTransactionIntegrationTest','StockAdmissionIntegrationTest',
    'OrderTransactionIntegrationTest','AsyncPurchaseIntegrationTest','OrderReviewIntegrationTest',
    'PaymentTransactionIntegrationTest','RedisSessionIntegrationTest')
$seen = @{}
$tests = $failures = $errors = $skipped = 0
foreach ($module in Get-ChildItem -LiteralPath $root -Directory -Filter 'ticket-*') {
    $reports = Join-Path $module.FullName 'target/surefire-reports'
    if (-not (Test-Path -LiteralPath $reports)) { continue }
    foreach ($file in Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml') {
        [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
        $suite = $report.testsuite
        $tests += [int]$suite.tests; $failures += [int]$suite.failures
        $errors += [int]$suite.errors; $skipped += [int]$suite.skipped
        $name = ([string]$suite.name).Split('.')[-1]
        if ([int]$suite.tests -gt 0 -and [int]$suite.skipped -eq 0) { $seen[$name] = $true }
    }
}
$missing = @($required | Where-Object { -not $seen.ContainsKey($_) })
Write-Output "tests=$tests failures=$failures errors=$errors skipped=$skipped"
if ($missing.Count -or $failures -or $errors -or $skipped) {
    throw "集成门禁未通过。未执行的关键测试：$($missing -join ', ')。"
}
