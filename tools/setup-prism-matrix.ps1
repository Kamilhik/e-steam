param(
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher",
    [string]$ReleaseDirectory = "release/0.2.0",
    [string]$Version = "0.2.0",
    [switch]$E4steamOnly
)

$ErrorActionPreference = "Stop"
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$instancesRoot = Join-Path $PrismRoot "instances"
$metaBase = "https://meta.prismlauncher.org/v1"

function Write-Utf8([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Get-Index([string]$Uid) {
    return Invoke-RestMethod "$metaBase/$Uid/index.json" -TimeoutSec 60
}

function Get-LatestLoader([string]$Uid, [string]$Minecraft, [object]$Index) {
    if ($Uid -in @("net.fabricmc.fabric-loader", "org.quiltmc.quilt-loader")) {
        $match = $Index.versions | Where-Object recommended | Select-Object -First 1
    } else {
        $match = $Index.versions | Where-Object {
            @($_.requires | Where-Object { $_.uid -eq "net.minecraft" -and $_.equals -eq $Minecraft }).Count -gt 0
        } | Sort-Object @{Expression={if ($_.recommended) { 0 } else { 1 }}}, @{Expression={[datetime]$_.releaseTime};Descending=$true} | Select-Object -First 1
    }
    if (-not $match) {
        throw "No $Uid loader metadata exists for Minecraft $Minecraft"
    }
    return $match.version
}

function Get-FabricApi([string]$Minecraft) {
    $versions = [uri]::EscapeDataString((ConvertTo-Json -InputObject @($Minecraft) -Compress))
    $loaders = [uri]::EscapeDataString((ConvertTo-Json -InputObject @("fabric") -Compress))
    $items = Invoke-RestMethod "https://api.modrinth.com/v2/project/P7dR8mSH/version?game_versions=$versions&loaders=$loaders" -TimeoutSec 60
    $release = @($items | Where-Object version_type -eq "release" | Select-Object -First 1)
    if ($release.Count -eq 0) {
        $release = @($items | Select-Object -First 1)
    }
    if ($release.Count -eq 0) {
        throw "No Fabric API file exists for Minecraft $Minecraft"
    }
    $file = @($release[0].files | Where-Object primary | Select-Object -First 1)
    if ($file.Count -eq 0) {
        $file = @($release[0].files | Select-Object -First 1)
    }
    return $file[0]
}

function Get-E4steamArtifact([string]$Loader, [string]$Minecraft) {
    if ($Loader -in @("fabric", "quilt")) {
        if ($Minecraft -in $legacyFabricVersions) {
            return "e4steam-fabric-quilt-mc1.17-1.18.2-v$Version.jar"
        }
        if ($Minecraft -in $modernVersions) {
            return "e4steam-fabric-quilt-mc26.1-26.2-v$Version.jar"
        }
        return "e4steam-fabric-quilt-mc1.19-1.21.11-v$Version.jar"
    }
    if ($Loader -eq "forge") {
        if ($Minecraft -in $legacyForgeVersions) {
            return "e4steam-forge-mc1.17.1-1.18.1-v$Version.jar"
        }
        return "e4steam-forge-mc1.18.2-1.20.2-v$Version.jar"
    }
    return "e4steam-neoforge-mc1.20.2-26.2-v$Version.jar"
}

$legacyFabricVersions = @("1.17", "1.17.1", "1.18", "1.18.1", "1.18.2")
$legacyForgeVersions = @("1.17.1", "1.18", "1.18.1")
$fabricVersions = @(
    "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
    "1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
    "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
)
$modernVersions = @("26.1", "26.1.1", "26.1.2", "26.2")
$forgeVersions = @(
    "1.18.2", "1.19", "1.19.1", "1.19.2", "1.19.3", "1.19.4",
    "1.20", "1.20.1", "1.20.2"
)
$neoForgeVersions = @(
    "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6",
    "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
) + $modernVersions

$matrix = @()
foreach ($mc in ($legacyFabricVersions + $fabricVersions + $modernVersions)) {
    $matrix += [pscustomobject]@{ Loader="fabric"; Minecraft=$mc; Uid="net.fabricmc.fabric-loader" }
    $matrix += [pscustomobject]@{ Loader="quilt"; Minecraft=$mc; Uid="org.quiltmc.quilt-loader" }
}
foreach ($mc in ($legacyForgeVersions + $forgeVersions)) {
    $matrix += [pscustomobject]@{ Loader="forge"; Minecraft=$mc; Uid="net.minecraftforge" }
}
foreach ($mc in $neoForgeVersions) {
    $matrix += [pscustomobject]@{ Loader="neoforge"; Minecraft=$mc; Uid="net.neoforged" }
}

if ($E4steamOnly) {
    foreach ($entry in $matrix) {
        $instanceId = "e4steam-$($entry.Loader)-$($entry.Minecraft.Replace('.', '_'))"
        $modsPath = Join-Path (Join-Path $instancesRoot $instanceId) ".minecraft\mods"
        if (-not (Test-Path -LiteralPath $modsPath)) {
            continue
        }
        Get-ChildItem -LiteralPath $modsPath -Filter "e4steam-*.jar" -File -ErrorAction SilentlyContinue |
            Remove-Item -Force
        $artifact = Get-E4steamArtifact $entry.Loader $entry.Minecraft
        Copy-Item -LiteralPath (Join-Path $ReleaseDirectory $artifact) -Destination $modsPath -Force
    }
    Write-Host "Updated e4steam in $($matrix.Count) Prism compatibility instances."
    exit 0
}

$indexes = @{}
foreach ($uid in ($matrix.Uid | Sort-Object -Unique)) {
    $indexes[$uid] = Get-Index $uid
}
$fabricApiCache = @{}
$created = @()

foreach ($entry in $matrix) {
    $instanceId = "e4steam-$($entry.Loader)-$($entry.Minecraft.Replace('.', '_'))"
    $instancePath = Join-Path $instancesRoot $instanceId
    $minecraftPath = Join-Path $instancePath ".minecraft"
    $modsPath = Join-Path $minecraftPath "mods"
    $null = New-Item -ItemType Directory -Path $modsPath -Force

    $loaderVersion = Get-LatestLoader $entry.Uid $entry.Minecraft $indexes[$entry.Uid]
    $components = @(
        [ordered]@{ important=$true; uid="net.minecraft"; version=$entry.Minecraft },
        [ordered]@{ uid=$entry.Uid; version=$loaderVersion }
    )
    $pack = [ordered]@{ formatVersion=1; components=$components }
    Write-Utf8 (Join-Path $instancePath "mmc-pack.json") ($pack | ConvertTo-Json -Depth 8)

    $cfg = @"
[General]
ConfigVersion=1.3
InstanceType=OneSix
iconKey=default
name=e4steam $($entry.Loader) $($entry.Minecraft)
AutomaticJava=true
OverrideJavaLocation=false
ShowConsole=false
ShowConsoleOnError=true
MaxMemAlloc=2048
MinMemAlloc=512
MinecraftWinHeight=480
MinecraftWinWidth=854
"@
    Write-Utf8 (Join-Path $instancePath "instance.cfg") $cfg

    Get-ChildItem -LiteralPath $modsPath -Filter "e4steam-*.jar" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force
    Get-ChildItem -LiteralPath $modsPath -Filter "fabric-api-*.jar" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force
    $artifact = Get-E4steamArtifact $entry.Loader $entry.Minecraft
    Copy-Item -LiteralPath (Join-Path $ReleaseDirectory $artifact) -Destination $modsPath -Force

    if ($entry.Loader -in @("fabric", "quilt")) {
        if (-not $fabricApiCache.ContainsKey($entry.Minecraft)) {
            $fabricApiCache[$entry.Minecraft] = Get-FabricApi $entry.Minecraft
        }
        $apiFile = $fabricApiCache[$entry.Minecraft]
        $apiPath = Join-Path $modsPath $apiFile.filename
        if (-not (Test-Path -LiteralPath $apiPath)) {
            Invoke-WebRequest $apiFile.url -OutFile $apiPath -TimeoutSec 120
        }
    }

    $created += $instanceId
    Write-Host "Prepared $instanceId ($loaderVersion)"
}

$groupsPath = Join-Path $instancesRoot "instgroups.json"
$existingGroups = if (Test-Path -LiteralPath $groupsPath) {
    Get-Content -LiteralPath $groupsPath -Raw -Encoding UTF8 | ConvertFrom-Json
} else { $null }
$groupTable = @{}
if ($existingGroups -and $existingGroups.groups) {
    $existingGroups.groups.psobject.Properties | ForEach-Object {
        $groupTable[$_.Name] = $_.Value
    }
}
$groupTable["e4steam compatibility"] = @{
    hidden = $false
    instances = @($created)
}
$groups = [ordered]@{ formatVersion="1"; groups=$groupTable }
Write-Utf8 $groupsPath ($groups | ConvertTo-Json -Depth 8)

Write-Host "Prepared $($created.Count) Prism Launcher compatibility instances."
