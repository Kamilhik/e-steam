# Release checklist

e4steam 0.2.0 is the current stable release. Public builds use Steam App ID
480 and are published as client-only release files for Windows x64 and
experimental Linux x64 support.

## Before publishing

1. Update `CHANGELOG.md` in English first and Russian second.
2. Update `COMPATIBILITY.md` with launch and host/guest results. Do not turn an
   untested combination into a verified one.
3. Run the complete verification command below on Windows.
4. Push the release commit and wait for both Linux and Windows GitHub Actions
   jobs to pass.
5. Create the annotated `v<version>` tag from that verified commit.
6. Publish the six runtime JARs as stable releases.

## Supported files

| File | Loader metadata | Game versions | Required dependency |
| --- | --- | --- | --- |
| `e4steam-fabric-<version>-mc1.17-1.18.2.jar` | Fabric and Quilt | 1.17–1.18.2 | Fabric API |
| `e4steam-forge-<version>-legacy17.jar` | Forge | 1.17.1–1.18.1 | None |
| `e4steam-fabric-<version>.jar` | Fabric and Quilt | 1.19–1.21.11 | Fabric API |
| `e4steam-fabric-<version>-modern.jar` | Fabric and Quilt | 26.1–26.2; 26.3 Snapshot 7 | Fabric API |
| `e4steam-forge-<version>.jar` | Forge | 1.18.2–1.20.2 | None |
| `e4steam-neoforge-<version>.jar` | NeoForge | 1.20.2–26.2 | None |

Use release channel **Release**, environment **Client required / Server
unsupported**. Do not upload `dev-shadow`, `sources`, `unstubbed`, or
root-project JARs. Forge 1.17.0 has no supported loader target, so the legacy
Forge range begins at 1.17.1.

Do not create OS-specific duplicates. Every runtime JAR contains both the
Windows x64 (`steam_api64.dll`, `steamworks4j64.dll`) and Linux x64
(`libsteam_api.so`, `libsteamworks4j.so`) native libraries and selects the
correct pair at runtime.

## Compatibility verification

Client launch testing and multiplayer testing are different gates:

- client launch: Minecraft reaches the main menu with e4steam and required
  dependencies loaded;
- multiplayer: a host opens a world, a second Steam account joins, TCP traffic
  works, the invitation path works, and configured UDP voice traffic is tested.

For a new release, retest the lower and upper boundary of every changed
artifact. Record the Minecraft version, loader, Java version, operating system,
host and guest result, invitation result, TCP result, UDP result, and date in
`COMPATIBILITY.md`. Multiplayer confirmation remains a manual two-client test;
unit tests and a successful main-menu launch do not replace it.

## Listing disclosures

The project page must state that e4steam is an unofficial derivative of e4mc,
that both players need the mod and a signed-in Steam client, that traffic uses
Steam P2P or Valve relays, that native Steamworks redistributables are bundled,
and that App ID 480 is a shared test namespace used permanently by this fork.

The public display name is **e4steam**. Identify **Kamilchik** as the project
author and current maintainer, retain the e4mc fork attribution, and preserve
the notices in `LICENSE` and `THIRD_PARTY_NOTICES.md`.

## Verification

```powershell
.\gradlew.bat --no-daemon clean releaseJars
git diff --check
$releaseJars = Get-ChildItem release\0.2.0\*.jar
if ($releaseJars.Count -ne 6) { throw "Expected 6 runtime JARs, found $($releaseJars.Count)" }
$releaseJars | Get-FileHash -Algorithm SHA256
```

For Shift+Tab, launch the Minecraft launcher through a Steam non-Steam shortcut
and verify that the in-game invitation action opens the Steam overlay.
