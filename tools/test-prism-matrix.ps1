param(
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher",
    [string]$PrismExe = "$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe",
    [int]$TimeoutSeconds = 180,
    [int]$BatchSize = 4,
    [string]$InstancePattern = "e4steam-*",
    [string]$ResultsFile = "build/client-compatibility.json"
)

$ErrorActionPreference = "Stop"
$instancesRoot = (Resolve-Path -LiteralPath (Join-Path $PrismRoot "instances")).Path
$resultsPath = if ([IO.Path]::IsPathRooted($ResultsFile)) {
    $ResultsFile
} else {
    Join-Path (Get-Location) $ResultsFile
}
$resultsDirectory = Split-Path -Parent $resultsPath
if ($resultsDirectory) { $null = New-Item -ItemType Directory -Path $resultsDirectory -Force }
$resultById = @{}
if (Test-Path -LiteralPath $resultsPath) {
    $loadedResults = Get-Content -LiteralPath $resultsPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($loadedResult in $loadedResults) {
        if ($loadedResult.instance) {
            $resultById[$loadedResult.instance] = $loadedResult
        }
    }
}

function Save-Results {
    $ordered = @($resultById.Values | Sort-Object instance)
    [IO.File]::WriteAllText(
        $resultsPath,
        ($ordered | ConvertTo-Json -Depth 5),
        [Text.UTF8Encoding]::new($false)
    )
}

$instanceIds = @(Get-ChildItem -LiteralPath $instancesRoot -Directory |
    Where-Object Name -Like $InstancePattern |
    Select-Object -ExpandProperty Name |
    Where-Object { -not ($resultById.ContainsKey($_) -and $resultById[$_].status -eq "started") } |
    Sort-Object)

for ($offset = 0; $offset -lt $instanceIds.Count; $offset += $BatchSize) {
    $last = [Math]::Min($offset + $BatchSize - 1, $instanceIds.Count - 1)
    $batch = @($instanceIds[$offset..$last])
    $states = @{}
    Write-Host "Starting batch $($offset + 1)-$($last + 1) of $($instanceIds.Count): $($batch -join ', ')"

    foreach ($instanceId in $batch) {
        $instancePath = Join-Path $instancesRoot $instanceId
        if (-not $instancePath.StartsWith($instancesRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Unsafe instance path: $instancePath"
        }
        $logPath = Join-Path $instancePath ".minecraft\logs\latest.log"
        if (Test-Path -LiteralPath $logPath) {
            Remove-Item -LiteralPath $logPath -Force
        }
        $cfgPath = Join-Path $instancePath "instance.cfg"
        $cfg = Get-Content -LiteralPath $cfgPath -Raw -Encoding UTF8
        $cfg = $cfg -replace "(?m)^MaxMemAlloc=\d+$", "MaxMemAlloc=2048"
        [IO.File]::WriteAllText($cfgPath, $cfg, [Text.UTF8Encoding]::new($false))
        $states[$instanceId] = [ordered]@{
            path = $instancePath
            log = $logPath
            started = Get-Date
            status = "running"
            detail = ""
        }
        Start-Process -FilePath $PrismExe -ArgumentList @("--launch", $instanceId) -WindowStyle Hidden | Out-Null
        Start-Sleep -Milliseconds 750
    }

    $batchStarted = Get-Date
    while ($true) {
        $runningCount = @($states.Values | Where-Object status -eq "running").Count
        $batchElapsed = ((Get-Date) - $batchStarted).TotalSeconds
        if ($runningCount -eq 0 -or $batchElapsed -ge $TimeoutSeconds) {
            break
        }
        Start-Sleep -Seconds 2
        foreach ($instanceId in $batch) {
            $state = $states[$instanceId]
            if ($state.status -ne "running" -or -not (Test-Path -LiteralPath $state.log)) {
                continue
            }
            $log = Get-Content -LiteralPath $state.log -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($log -match '(?im)(Incompatible mods found|Mod resolution failed|Mixin apply failed|Could not execute entrypoint|Exception in thread "main"|The game crashed)') {
                $state.status = "failed"
                $state.detail = $matches[1]
                Write-Host "$instanceId -> failed ($($state.detail))"
            } elseif ($log -match "(?im)(Backend library: LWJGL|Narrator library for x64 successfully loaded|Sound engine started|Created: \d+x\d+)") {
                $state.status = "started"
                $state.detail = "Minecraft client reached main-menu initialization with e4steam installed"
                Write-Host "$instanceId -> started"
            }
        }
    }

    foreach ($instanceId in $batch) {
        $state = $states[$instanceId]
        if ($state.status -eq "running") {
            $state.status = "timeout"
            $state.detail = "Minecraft did not reach the client-ready marker before timeout"
            Write-Host "$instanceId -> timeout"
        }
        $needle = [Regex]::Escape((Join-Path "instances" $instanceId).Replace('\', '/'))
        Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
            Where-Object { $_.CommandLine -and $_.CommandLine.Replace('\', '/') -match $needle } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

        $parts = $instanceId.Split("-")
        $resultById[$instanceId] = [pscustomobject]@{
            instance = $instanceId
            loader = $parts[1]
            minecraft = ($parts[2..($parts.Length - 1)] -join "-").Replace("_", ".")
            status = $state.status
            detail = $state.detail
            startedAt = $state.started.ToString("o")
            durationSeconds = [int]((Get-Date) - $state.started).TotalSeconds
        }
    }
    Save-Results
}

Save-Results
Write-Host "Stored $($resultById.Count) Prism launch checks."
