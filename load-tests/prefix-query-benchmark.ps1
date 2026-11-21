param(
    [string]$BaseUrl = 'http://localhost:18080',
    [ValidateRange(10, 10000)]
    [int]$Requests = 100,
    [ValidateRange(1, 200)]
    [int]$Concurrency = 20,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    throw 'curl.exe is required'
}

$scenarios = @(
    [pscustomobject]@{
        Name = 'prefix-9k'
        CandidateRows = 9000
        Url = "$BaseUrl/api/v1/books?q=Load%20Test%20Book%20499&size=20"
    },
    [pscustomobject]@{
        Name = 'prefix-90k'
        CandidateRows = 90000
        Url = "$BaseUrl/api/v1/books?q=Load%20Test%20Book%2049&size=20"
    },
    [pscustomobject]@{
        Name = 'prefix-9k-filtered'
        CandidateRows = 2333
        Url = "$BaseUrl/api/v1/books?q=Load%20Test%20Book%20499&categoryId=2&language=en&format=EBOOK&size=20"
    }
)

function Get-Percentile {
    param([double[]]$SortedSamples, [double]$Percentile)

    $index = [Math]::Ceiling($SortedSamples.Count * $Percentile) - 1
    return $SortedSamples[[Math]::Max(0, [Math]::Min($index, $SortedSamples.Count - 1))]
}

function Invoke-PrefixQueryBenchmark {
    param([pscustomobject]$Scenario, [string]$RunRoot)

    $probe = Invoke-RestMethod -Uri $Scenario.Url -TimeoutSec 15
    $probeCode = [string]$probe.code

    $scenarioDirectory = Join-Path $RunRoot $Scenario.Name
    New-Item -ItemType Directory -Path $scenarioDirectory | Out-Null
    $curlDirectory = $scenarioDirectory.Replace('\', '/')

    $config = [System.Collections.Generic.List[string]]::new()
    $config.Add('silent')
    $config.Add('show-error')
    $config.Add('parallel')
    $config.Add("parallel-max = $Concurrency")
    $config.Add('write-out = "%{http_code} %{time_total}\n"')
    1..$Requests | ForEach-Object {
        $responsePath = "$curlDirectory/$($_.ToString('D5')).json"
        $config.Add("output = `"$responsePath`"")
        $config.Add("url = `"$($Scenario.Url)`"")
    }

    $timer = [Diagnostics.Stopwatch]::StartNew()
    $lines = $config | & curl.exe --config -
    $timer.Stop()
    if ($LASTEXITCODE -ne 0) {
        throw "curl load failed for $($Scenario.Name)"
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

    $businessSuccess = 0
    $internalError = 0
    $otherBusinessCode = 0
    $invalidJson = 0
    Get-ChildItem -LiteralPath $scenarioDirectory -Filter '*.json' | ForEach-Object {
        try {
            $body = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
            if ($body.code -eq '0000') {
                $businessSuccess++
            } elseif ($body.code -eq 'INTERNAL_ERROR') {
                $internalError++
            } else {
                $otherBusinessCode++
            }
        } catch {
            $invalidJson++
        }
    }

    if ($samples.Count -ne $Requests -or $http200 -ne $Requests) {
        throw "Invalid HTTP sample set for $($Scenario.Name): samples=$($samples.Count), HTTP200=$http200"
    }
    if (($businessSuccess + $internalError + $otherBusinessCode + $invalidJson) -ne $Requests) {
        throw "Invalid response set for $($Scenario.Name)"
    }

    [double[]]$sorted = $samples | Sort-Object
    [pscustomobject]@{
        Scenario = $Scenario.Name
        CandidateRows = $Scenario.CandidateRows
        ProbeCode = $probeCode
        Requests = $Requests
        Concurrency = $Concurrency
        Rps = [Math]::Round($Requests / $timer.Elapsed.TotalSeconds, 2)
        P50Ms = [Math]::Round((Get-Percentile $sorted 0.50), 2)
        P95Ms = [Math]::Round((Get-Percentile $sorted 0.95), 2)
        P99Ms = [Math]::Round((Get-Percentile $sorted 0.99), 2)
        MaxMs = [Math]::Round($sorted[-1], 2)
        Http200 = $http200
        BusinessSuccess = $businessSuccess
        InternalError = $internalError
        OtherBusinessCode = $otherBusinessCode
        InvalidJson = $invalidJson
    }
}

$runRoot = Join-Path ([System.IO.Path]::GetTempPath()) "elibrary-prefix-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $runRoot | Out-Null

try {
    $results = foreach ($scenario in $scenarios) {
        Invoke-PrefixQueryBenchmark -Scenario $scenario -RunRoot $runRoot
    }
} finally {
    if (Test-Path -LiteralPath $runRoot) {
        Remove-Item -LiteralPath $runRoot -Recurse -Force
    }
}

$results | Format-Table -AutoSize

if ($OutputPath) {
    $now = Get-Date
    $report = [ordered]@{
        completedAt = $now.ToString('yyyy-MM-dd HH:mm:ss.fff zzz')
        completedAtUtc = $now.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        baseUrl = $BaseUrl
        dataset = '5,000,101 books; 5,000,000 generated benchmark rows'
        query = "title LIKE CONCAT(query, '%')"
        statementTimeoutSeconds = 3
        requestsPerScenario = $Requests
        concurrency = $Concurrency
        latencyMeaning = 'HTTP response latency; interpret together with the business-code counts'
        results = $results
    }
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding utf8
    Write-Host "Saved benchmark results to $OutputPath"
}
