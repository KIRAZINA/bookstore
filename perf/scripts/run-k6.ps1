param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("baseline", "load", "stress", "spike", "endurance", "inventory")]
    [string]$Test,

    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

$scriptMap = @{
    baseline   = "perf/k6/tests/01-baseline.js"
    load       = "perf/k6/tests/02-load.js"
    stress     = "perf/k6/tests/03-stress.js"
    spike      = "perf/k6/tests/04-spike.js"
    endurance  = "perf/k6/tests/05-endurance.js"
    inventory  = "perf/k6/tests/06-inventory-contention.js"
}

$k6Script = $scriptMap[$Test]
if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
    Write-Error "k6 is not installed. Install from https://k6.io/docs/get-started/installation/"
}

Write-Host "Running k6 $Test test against $BaseUrl"
$env:BASE_URL = $BaseUrl
k6 run $k6Script
