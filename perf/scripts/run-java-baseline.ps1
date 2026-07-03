param(
    [int]$Iterations = 50,
    [int]$Warmup = 5
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $repoRoot

Write-Host "Running JVM performance baseline (iterations=$Iterations, warmup=$Warmup)"
mvn test -Pperformance "-Dperf.iterations=$Iterations" "-Dperf.warmup=$Warmup"
