# Release checklist

## Current gate

`0.2.0-alpha.2` is an unreleased development build. Do not upload its binary
JARs to Modrinth or attach them to a public GitHub release until every
loader/version combination below has passed a clean-instance smoke test, the
publisher has personally accepted the applicable Steamworks terms, and Valve
has confirmed the intended public App ID 480 use, or the project has moved to
an approved App ID.

The repository CI intentionally compiles and tests without publishing binary artifacts.

## Publishing the source repository

The MIT-licensed source repository may be made public independently of a binary
release. Before the initial push:

- replace placeholder Git author details with the publisher's real or GitHub
  noreply identity;
- run the full verification below and inspect `git status --short`;
- verify that no build output, Minecraft instance, logs, credentials, signing
  material, or generated App ID files are tracked;
- keep GitHub Actions permissions read-only and do not add release/upload steps
  while the binary gate remains closed; and
- enable GitHub private vulnerability reporting after repository creation.

Do not create a release tag for an unreleased compatibility matrix. A source
commit and a downloadable GitHub/Modrinth binary are separate publication
decisions.

## Supported files

Create six files under one Modrinth project:

| File | Loader metadata | Game versions | Required dependency |
| --- | --- | --- | --- |
| `e4steam-fabric-<version>-mc1.17-1.18.2.jar` | Fabric and Quilt | 1.17-1.18.2 | Fabric API |
| `e4steam-forge-<version>-legacy17.jar` | Forge | 1.17.1–1.18.1 | None |
| `e4steam-fabric-<version>.jar` | Fabric and Quilt | 1.19-1.21.11 | Fabric API |
| `e4steam-fabric-<version>-modern.jar` | Fabric and Quilt | 26.1–26.2 | Fabric API |
| `e4steam-forge-<version>.jar` | Forge | 1.18.2–1.20.2 | None |
| `e4steam-neoforge-<version>.jar` | NeoForge | 1.20.2–26.2 | None |

Use release channel **Alpha**, environment **Client required / Server unsupported**. Do not upload `dev-shadow`, `sources`, `unstubbed`, or root-project JARs. A separate installer is not needed for Modrinth.

These are declared compatibility ranges, not proof that every version has been
tested. The two `legacy17` artifacts are experimental and target Java 16
bytecode (`legacy17` refers to the Minecraft 1.17 line, not Java 17). Forge has
no 1.17.0 loader target, so its legacy range begins at 1.17.1.
Record the exact Minecraft, loader, Java, operating system, host, guest, and
overlay result for each smoke test. Minecraft 1.20.2 on Fabric, Forge, and
NeoForge is the only previously smoke-tested baseline.

At minimum, exercise every meaningful compatibility boundary before turning the
declared ranges into public release metadata:

| Minecraft | Required loader checks |
| --- | --- |
| 1.17 and 1.17.1 | Fabric/Quilt legacy on both; Forge legacy on 1.17.1 |
| 1.18, 1.18.1, and 1.18.2 | Fabric/Quilt legacy on all three; Forge legacy on 1.18 and 1.18.1, then standard on 1.18.2 |
| 1.19.2 and 1.19.4 | Fabric, Quilt, Forge |
| 1.20.2 | Fabric, Quilt, Forge, NeoForge |
| 1.20.5 | Fabric, Quilt, NeoForge |
| 1.21.1 and 1.21.11 | Fabric, Quilt, NeoForge |
| 26.1 and 26.2 | Fabric Modern, Quilt Modern, NeoForge |

For each row, verify both host and guest, friends-only and invitation-only
lobbies, direct-address fallback where allowed, clean shutdown, and the
Shift+Tab overlay when available.

## Listing disclosures

The project page must clearly state that this is an unofficial derivative of e4mc, that both players need the mod and a signed-in Steam client, that traffic uses Steam P2P/Valve relays, that native Steamworks redistributables are bundled, that App ID 480 is a shared test namespace, and that this fork uses the separate `e4steam` project and mod identity.

The public display name is **e4steam**. Identify **Kamilchik** as the project
author and current maintainer, keep Skye and the original e4mc contributors in
the fork attribution, and preserve both copyright lines in `LICENSE` together
with `THIRD_PARTY_NOTICES.md`.

## Verification

```powershell
.\gradlew.bat clean releaseJars
git diff --check
$releaseJars = Get-ChildItem legacy\fabric\build\libs\*.jar,legacy\forge\build\libs\*.jar,fabric\build\libs\*.jar,forge\build\libs\*.jar,neoforge\build\libs\*.jar |
    Where-Object Name -NotMatch 'dev-shadow|sources'
if ($releaseJars.Count -ne 6) { throw "Expected 6 runtime JARs, found $($releaseJars.Count)" }
$releaseJars | Get-FileHash -Algorithm SHA256
```

Test the matching JAR in a clean instance for every advertised Minecraft/loader
combination. For Shift+Tab, launch Prism Launcher from a Steam non-Steam
shortcut and verify that the in-game Steam button reports the overlay as
available.
