param(
    [string]$BaseUrl = 'http://localhost:18080',
    [ValidateRange(100, 10000)]
    [int]$Requests = 1000,
    [ValidateRange(1, 200)]
    [int]$Concurrency = 20,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    throw 'curl.exe is required'
}

$scenarios = [ordered]@{
    'cursor-0pct' = "$BaseUrl/api/v1/books?size=20"
    'cursor-25pct' = "$BaseUrl/api/v1/books?afterBookId=4250000&size=20"
    'cursor-50pct' = "$BaseUrl/api/v1/books?afterBookId=5500000&size=20"
    'cursor-75pct' = "$BaseUrl/api/v1/books?afterBookId=6750000&size=20"
    'cursor-90pct' = "$BaseUrl/api/v1/books?afterBookId=7500000&size=20"
    'cursor-99pct' = "$BaseUrl/api/v1/books?afterBookId=7950000&size=20"
    'cursor-99.9pct' = "$BaseUrl/api/v1/books?afterBookId=7995000&size=20"
    'cursor-99pct-filtered' = "$BaseUrl/api/v1/books?afterBookId=7950000&categoryId=2&language=en&format=EBOOK&size=20"
}

function Get-Percentile {
    param([double[]]$SortedSamples, [double]$Percentile)

    $index = [Math]::Ceiling($SortedSamples.Count * $Percentile) - 1
    return $SortedSamples[[Math]::Max(0, [Math]::Min($index, $SortedSamples.Count - 1))]
}

function Invoke-DeepCursorBenchmark {
    param([string]$Name, [string]$Url)

    $probe = Invoke-RestMethod -Uri $Url -TimeoutSec 10
    if ($probe.code -ne '0000' -or $probe.data.items.Count -ne 20) {
        throw "Probe failed for ${Name}: expected code 0000 and 20 items"
    }

    1..20 | ForEach-Object {
        & curl.exe -sS -o NUL $Url
        if ($LASTEXITCODE -ne 0) {
            throw "Warm-up request failed for $Name"
        }
    }

    $config = [System.Collections.Generic.List[string]]::new()
    $config.Add('silent')
    $config.Add('show-error')
    $config.Add('parallel')
    $config.Add("parallel-max = $Concurrency")
    $config.Add('write-out = "%{http_code} %{time_total}\n"')
    1..$Requests | ForEach-Object {
        $config.Add('output = "NUL"')
        $config.Add("url = `"$Url`"")
    }

    $timer = [Diagnostics.Stopwatch]::StartNew()
    $lines = $config | & curl.exe --config -
    $timer.Stop()
    if ($LASTEXITCODE -ne 0) {
        throw "curl load failed for $Name"
    }

    $samples = [System.Collections.Generic.List[double]]::new()
    $http200 = 0
    foreach ($line in $lines) {
        if ($line -notmatch '^(\d{3})\s+([0-9.]+)$') {
            continue
        }
        if ($matches[1] -eq '200') {
            $http200++
        }
        $samples.Add(([double]$matches[2]) * 1000)
    }

    if ($samples.Count -ne $Requests -or $http200 -ne $Requests) {
        throw "Invalid sample set for ${Name}: samples=$($samples.Count), HTTP200=$http200"
    }

    [double[]]$sorted = $samples | Sort-Object
    [pscustomobject]@{
        Scenario = $Name
        Requests = $Requests
        Concurrency = $Concurrency
        Rps = [Math]::Round($Requests / $timer.Elapsed.TotalSeconds, 1)
        P50Ms = [Math]::Round((Get-Percentile $sorted 0.50), 2)
        P95Ms = [Math]::Round((Get-Percentile $sorted 0.95), 2)
        P99Ms = [Math]::Round((Get-Percentile $sorted 0.99), 2)
        MaxMs = [Math]::Round($sorted[-1], 2)
        Http200 = $http200
    }
}

$results = foreach ($scenario in $scenarios.GetEnumerator()) {
    Invoke-DeepCursorBenchmark -Name $scenario.Key -Url $scenario.Value
}

$results | Format-Table -AutoSize

if ($OutputPath) {
    $now = Get-Date
    $report = [ordered]@{
        completedAt = $now.ToString('yyyy-MM-dd HH:mm:ss.fff zzz')
        completedAtUtc = $now.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        baseUrl = $BaseUrl
        dataset = '5,000,101 books; 5,000,000 generated benchmark rows'
        pagination = 'afterBookId keyset cursor'
        requestsPerScenario = $Requests
        concurrency = $Concurrency
        results = $results
    }
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding utf8
    Write-Host "Saved benchmark results to $OutputPath"
}
