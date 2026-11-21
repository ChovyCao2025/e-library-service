param(
    [string]$BaseUrl = 'http://localhost:8080',
    [ValidateRange(1, 10000)]
    [int]$Requests = 300,
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
    'list-first-page' = "$BaseUrl/api/v1/books?size=20"
    'combined-filter' = "$BaseUrl/api/v1/books?categoryId=2&language=en&format=EBOOK&size=20"
    'title-prefix-9k' = "$BaseUrl/api/v1/books?q=Load%20Test%20Book%2009&size=20"
    'title-prefix-900' = "$BaseUrl/api/v1/books?q=Load%20Test%20Book%20099&size=20"
    'deep-cursor' = "$BaseUrl/api/v1/books?afterBookId=3099000&size=20"
    'deep-filter' = "$BaseUrl/api/v1/books?afterBookId=3040000&categoryId=2&language=en&format=EBOOK&size=20"
    'detail' = "$BaseUrl/api/v1/books/3099998"
}

function Get-Percentile {
    param(
        [double[]]$SortedSamples,
        [double]$Percentile
    )

    $index = [Math]::Ceiling($SortedSamples.Count * $Percentile) - 1
    return $SortedSamples[[Math]::Max(0, [Math]::Min($index, $SortedSamples.Count - 1))]
}

function Invoke-CurlBenchmark {
    param(
        [string]$Name,
        [string]$Url
    )

    1..10 | ForEach-Object {
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

    if ($samples.Count -ne $Requests) {
        throw "Expected $Requests samples for $Name, received $($samples.Count)"
    }
    if ($http200 -ne $Requests) {
        throw "Expected $Requests HTTP 200 responses for $Name, received $http200"
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
    Invoke-CurlBenchmark -Name $scenario.Key -Url $scenario.Value
}

$results | Format-Table -AutoSize

if ($OutputPath) {
    $now = Get-Date
    $computer = Get-CimInstance Win32_ComputerSystem
    $processor = Get-CimInstance Win32_Processor | Select-Object -First 1
    $operatingSystem = Get-CimInstance Win32_OperatingSystem
    $curlVersion = (& curl.exe --version | Select-Object -First 1)
    $report = [ordered]@{
        completedAt = $now.ToString('yyyy-MM-dd HH:mm:ss.fff zzz')
        completedAtUtc = $now.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        baseUrl = $BaseUrl
        requestsPerScenario = $Requests
        concurrency = $Concurrency
        client = $curlVersion
        environment = [ordered]@{
            machine = "$($computer.Manufacturer) $($computer.Model)"
            processor = $processor.Name.Trim()
            cores = $processor.NumberOfCores
            logicalProcessors = $processor.NumberOfLogicalProcessors
            memoryGiB = [Math]::Round($computer.TotalPhysicalMemory / 1GB, 2)
            operatingSystem = $operatingSystem.Caption
            operatingSystemVersion = $operatingSystem.Version
        }
        results = $results
    }
    $parent = Split-Path -Parent $OutputPath
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding utf8
    Write-Host "Saved benchmark results to $OutputPath"
}
