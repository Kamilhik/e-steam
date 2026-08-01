# Contributing

Thanks for helping with e4steam. This repository is an
unofficial derivative of e4mc; it is not affiliated with Valve, Steam, Mojang,
Microsoft, or the original e4mc maintainers.

The project author and current maintainer is **Kamilchik**.

## Before opening an issue

- Use GitHub's private vulnerability reporting for security problems.
- Never post a complete generated join address, Steam session details, private
  account information, or an unreviewed log in a public issue.
- Check that both players use the same mod release and a compatible Minecraft
  version and loader.

## Development setup

Install JDK 21, clone the repository, and run:

```powershell
.\gradlew.bat clean releaseJars
```

On Linux:

```bash
./gradlew clean releaseJars
```

The produced bytecode and supported game versions are defined by the checked-in
Gradle configuration. Do not claim compatibility based only on a Minecraft
version-family label; test every loader/version combination listed in release
metadata.

Before submitting a pull request, also run:

```powershell
git diff --check
.\gradlew.bat --no-daemon releaseJars
```

The aggregate task covers the two Minecraft 1.17 legacy artifacts, the standard
and modern Fabric variants, Forge, and NeoForge. Do not silently omit a variant
when changing it.

## Pull requests

- Keep changes focused and explain how they were tested.
- Add or update tests for protocol, address-parsing, and lifecycle changes.
- Update `CHANGELOG.md` and compatibility documentation for user-visible
  behavior.
- Do not commit build output, Minecraft instances, logs, credentials, signing
  keys, or generated `steam_appid.txt` files. The root `steam_appid.txt`
  containing only `480` is the intentional development fixture.
- Preserve the MIT license notices and update `THIRD_PARTY_NOTICES.md` when a
  dependency or bundled native library changes.

## Binary distribution

Source contributions are welcome under the repository's MIT License. A compiled
JAR bundles Valve Steamworks redistributables that are not covered by that
license. Stable binaries may be published after the verification and release
steps in `RELEASING.md` pass. Keep the release type set to **Release** and the
environment set to **Client required / Server unsupported**.
