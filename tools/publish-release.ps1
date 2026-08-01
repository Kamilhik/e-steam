param(
    [string]$Version = "0.2.0-alpha.4",
    [string]$SecretsFile = ".env.publisher",
    [string]$ReleaseDirectory = "release/0.2.0-alpha.4",
    [ValidateSet("all", "modrinth", "curseforge")]
    [string]$Target = "all",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

function Read-EnvFile([string]$Path) {
    $values = @{}
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) {
            continue
        }
        $key, $value = $line.Split("=", 2)
        $values[$key.Trim()] = $value.Trim()
    }
    return $values
}

function Get-Changelog([string]$Path, [string]$ReleaseVersion) {
    $text = Get-Content -LiteralPath $Path -Raw
    $escaped = [Regex]::Escape($ReleaseVersion)
    $match = [Regex]::Match(
        $text,
        "(?ms)^##\s+$escaped\s+-\s+[^\r\n]+\r?\n\r?\n(?<body>.*?)(?=^##\s+|\z)"
    )
    if (-not $match.Success) {
        throw "Could not find changelog section for $ReleaseVersion"
    }
    return $match.Groups["body"].Value.Trim()
}

function New-FileEntry(
    [string]$File,
    [string]$Label,
    [string[]]$Loaders,
    [string[]]$GameVersions,
    [string]$VersionSuffix = ""
) {
    return [PSCustomObject]@{
        File = $File
        Label = $Label
        Loaders = $Loaders
        GameVersions = $GameVersions
        VersionSuffix = $VersionSuffix
    }
}

function Send-Multipart(
    [string]$Uri,
    [hashtable]$Headers,
    [string]$MetadataField,
    [string]$MetadataJson,
    [string]$FileField,
    [string]$FilePath
) {
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $stream = $null
    try {
        foreach ($header in $Headers.GetEnumerator()) {
            $null = $client.DefaultRequestHeaders.TryAddWithoutValidation($header.Key, $header.Value)
        }

        $metadata = [System.Net.Http.StringContent]::new(
            $MetadataJson,
            [Text.Encoding]::UTF8,
            "application/json"
        )
        $multipart.Add($metadata, $MetadataField)

        $stream = [IO.File]::OpenRead($FilePath)
        $fileContent = [System.Net.Http.StreamContent]::new($stream)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new(
            "application/java-archive"
        )
        $multipart.Add($fileContent, $FileField, [IO.Path]::GetFileName($FilePath))

        $response = $client.PostAsync($Uri, $multipart).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Upload failed with HTTP $([int]$response.StatusCode): $body"
        }
        if ([string]::IsNullOrWhiteSpace($body)) {
            return $null
        }
        return $body | ConvertFrom-Json
    } finally {
        if ($stream) { $stream.Dispose() }
        $multipart.Dispose()
        $client.Dispose()
    }
}

function Publish-Modrinth(
    [hashtable]$Config,
    [object[]]$Entries,
    [string]$Changelog,
    [string]$ReleaseVersion,
    [switch]$Preview
) {
    $headers = @{
        Authorization = $Config.MODRINTH_TOKEN
        "User-Agent" = "Kamilhik/e-steam publisher"
    }
    $existing = Invoke-RestMethod `
        -Uri "https://api.modrinth.com/v2/project/$($Config.MODRINTH_PROJECT_ID)/version" `
        -Headers $headers

    foreach ($entry in $Entries) {
        $filePath = (Resolve-Path -LiteralPath (Join-Path $ReleaseDirectory $entry.File)).Path
        $alreadyUploaded = @($existing | Where-Object {
            @($_.files | ForEach-Object filename) -contains $entry.File
        }).Count -gt 0
        if ($alreadyUploaded) {
            Write-Host "[Modrinth] Skip existing: $($entry.File)"
            continue
        }

        $versionNumber = "$ReleaseVersion$($entry.VersionSuffix)"
        $metadata = @{
            project_id = $Config.MODRINTH_PROJECT_ID
            name = "[$($entry.Label)] e4steam $ReleaseVersion"
            version_number = $versionNumber
            changelog = $Changelog
            dependencies = @()
            game_versions = @($entry.GameVersions)
            version_type = "release"
            loaders = @($entry.Loaders)
            featured = $true
            status = "listed"
            file_parts = @("file")
            primary_file = "file"
            environment = "client_and_server"
        }
        if ($Preview) {
            Write-Host "[Modrinth] Would upload $($entry.File) as Release ($($entry.Label))"
            continue
        }

        $result = Send-Multipart `
            -Uri "https://api.modrinth.com/v2/version" `
            -Headers $headers `
            -MetadataField "data" `
            -MetadataJson ($metadata | ConvertTo-Json -Depth 8 -Compress) `
            -FileField "file" `
            -FilePath $filePath
        Write-Host "[Modrinth] Uploaded $($entry.File) -> version $($result.id)"
    }
}

function Publish-CurseForge(
    [hashtable]$Config,
    [object[]]$Entries,
    [string]$Changelog,
    [string]$ReleaseVersion,
    [switch]$Preview
) {
    $headers = @{ "X-Api-Token" = $Config.CURSEFORGE_TOKEN }
    foreach ($entry in $Entries) {
        $filePath = (Resolve-Path -LiteralPath (Join-Path $ReleaseDirectory $entry.File)).Path
        $metadata = @{
            changelog = $Changelog
            changelogType = "markdown"
            displayName = "[$($entry.Label)] e4steam $ReleaseVersion"
            gameVersionNames = @($entry.GameVersions) + @($entry.Loaders | ForEach-Object {
                switch ($_) {
                    "fabric" { "Fabric" }
                    "quilt" { "Quilt" }
                    "forge" { "Forge" }
                    "neoforge" { "NeoForge" }
                }
            }) + @("Client", "Server")
            releaseType = "release"
        }
        if ($Preview) {
            Write-Host "[CurseForge] Would upload $($entry.File) as Release ($($entry.Label))"
            continue
        }

        $result = Send-Multipart `
            -Uri "https://minecraft.curseforge.com/api/projects/$($Config.CURSEFORGE_PROJECT_ID)/upload-file" `
            -Headers $headers `
            -MetadataField "metadata" `
            -MetadataJson ($metadata | ConvertTo-Json -Depth 8 -Compress) `
            -FileField "file" `
            -FilePath $filePath
        Write-Host "[CurseForge] Uploaded $($entry.File) -> file $($result.id)"
    }
}

$config = Read-EnvFile $SecretsFile
foreach ($required in @(
    "MODRINTH_TOKEN",
    "CURSEFORGE_TOKEN",
    "MODRINTH_PROJECT_ID",
    "CURSEFORGE_PROJECT_ID"
)) {
    if ([string]::IsNullOrWhiteSpace($config[$required])) {
        throw "Missing $required in $SecretsFile"
    }
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

$entries = @(
    (New-FileEntry "e4steam-fabric-quilt-mc1.17-1.18.2-v$Version.jar" "Fabric/Quilt 1.17-1.18.2" @("fabric", "quilt") $legacyFabricVersions),
    (New-FileEntry "e4steam-forge-mc1.17.1-1.18.1-v$Version.jar" "Forge 1.17.1-1.18.1" @("forge") $legacyForgeVersions),
    (New-FileEntry "e4steam-fabric-quilt-mc1.19-1.21.11-v$Version.jar" "Fabric/Quilt 1.19-1.21.11" @("fabric", "quilt") $fabricVersions),
    (New-FileEntry "e4steam-fabric-quilt-mc26.1-26.2-v$Version.jar" "Fabric/Quilt 26.1-26.2" @("fabric", "quilt") $modernVersions "+modern"),
    (New-FileEntry "e4steam-forge-mc1.18.2-1.20.2-v$Version.jar" "Forge 1.18.2-1.20.2" @("forge") $forgeVersions),
    (New-FileEntry "e4steam-neoforge-mc1.20.2-26.2-v$Version.jar" "NeoForge 1.20.2-26.2" @("neoforge") $neoForgeVersions)
)

foreach ($entry in $entries) {
    $path = Join-Path $ReleaseDirectory $entry.File
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing release artifact: $path"
    }
}

$changelog = Get-Changelog "CHANGELOG.md" $Version
Write-Host "Publishing $($entries.Count) artifacts as Release; changelog length: $($changelog.Length)"

if ($Target -in @("all", "modrinth")) {
    Publish-Modrinth $config $entries $changelog $Version -Preview:$DryRun
}
if ($Target -in @("all", "curseforge")) {
    Publish-CurseForge $config $entries $changelog $Version -Preview:$DryRun
}
