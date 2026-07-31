# e4steam

An experimental e4mc fork that carries a Minecraft integrated-server connection through [Steam P2P](https://partner.steamgames.com/doc/features/multiplayer/networking). It uses Steam's development App ID **480** ([Spacewar](https://partner.steamgames.com/doc/sdk/api/example)) for private testing; it does not launch the Spacewar executable.

> This is a separate community project based on [the original e4mc mod](https://github.com/vgskye/e4mc-minecraft-architectury). It is not an official Valve, Steam, Mojang, or original e4mc release. The fork uses its own `e4steam` project and mod identity.

## What it does

- Shares a single-player world opened with **Open to LAN** without router port forwarding or a public IP address.
- Tries a direct Steam P2P connection first and can use a Valve relay when a direct route is unavailable.
- Creates a friends-only or invitation-only Steam lobby. Friends-only mode also supports a copied direct address as a fallback.
- Supports Shift+Tab invitations when the Steam Overlay is available.
- Keeps the Minecraft connection local on each computer while the mod transports its TCP stream through Steam.
- Starts Steamworks only while hosting, waiting for an invitation, connecting, or playing, then shuts it down automatically.

Both players must install the same e4steam build and use a compatible Minecraft loader/version.

## Compatibility

The `0.2.0-alpha.1` source tree builds six runtime JAR variants:

| Runtime JAR | Loader | Declared Minecraft range |
| --- | --- | --- |
| `e4steam-fabric-<version>-legacy17.jar` | Fabric; also intended for Quilt | 1.17.x |
| `e4steam-forge-<version>-legacy17.jar` | Forge | 1.17.1–1.18.1 |
| `e4steam-fabric-<version>.jar` | Fabric; also intended for Quilt | 1.18–1.21.11 |
| `e4steam-fabric-<version>-modern.jar` | Fabric; also intended for Quilt | 26.1–26.2 |
| `e4steam-forge-<version>.jar` | Forge | 1.18.2–1.20.2 |
| `e4steam-neoforge-<version>.jar` | NeoForge | 1.20.2–26.2 |

Minecraft 1.20.2 on Fabric, Forge, and NeoForge is the current smoke-tested
baseline. The wider ranges above are build/metadata targets and still require a
clean-instance smoke test for every listed Minecraft/loader combination before
public binary release. The two `legacy17` files are separate experimental
compatibility artifacts and target Java 16. The 1.20.2 transition intentionally
has both Forge and NeoForge builds; later versions use NeoForge instead of
Forge. Quilt uses the matching Fabric JAR rather than a separately renamed
artifact.

## Requirements

- Windows x64 or Linux x64.
- Steam desktop client running and signed in on both computers.
- Minecraft with the matching Fabric/Quilt, Forge, or NeoForge build of the mod.
- An integrated single-player server. Dedicated servers are not supported by the first release.

Use Java 16 for 1.17.x, Java 17 for 1.18–1.20.4, Java 21 for
1.20.5–1.21.x, and Java 25 for the 26.x line.

## Usage

1. Install the matching e4steam JAR on both computers and start/sign in to Steam.
2. For Shift+Tab, add Prism Launcher as a non-Steam game and launch it from the Steam library before starting the Minecraft instance. The mod reports a clear error if the overlay was not injected.
3. The host opens a single-player world, selects **Open to LAN**, then chooses **Steam friends** or **Invitation only**. **Local network only** leaves Steamworks completely off.
4. The guest opens **Multiplayer**; the mod automatically starts waiting for Steam invitations. The **Steam friends** button opens the overlay when it is available.
5. The host presses Shift+Tab and invites the guest, or uses **Invite Steam friends** in the pause menu/chat. The guest accepts the invitation or **Join Game** in Steam.
6. In **Steam friends** mode, if the overlay is unavailable, the host can copy the compact generated `s-...steam` address and the guest can use **Multiplayer → Direct Connection**. **Invitation only** deliberately requires the guest to enter the current private Steam lobby.

Do not start the actual Spacewar executable. The mod initializes Steamworks under App ID 480 inside the Minecraft process, so Steam may temporarily display Minecraft as Spacewar only while the Steam feature is active. The guest should open Minecraft's Multiplayer screen before accepting an App ID 480 invitation; Steam callbacks cannot be received while Steamworks is fully shut down.

## Network and security notes

- The mod does not configure your router or publish the LAN port to the public internet. Minecraft's normal **Open to LAN** listener still remains reachable from your existing local network according to your OS firewall rules.
- Steam chooses between a direct peer-to-peer route and its relay network.
- Every remote bridge must present the random 128-bit address token and come from a direct Steam friend of the host. Friends-only mode advertises **Join Game** and permits its copied address as a fallback. Invitation-only mode additionally requires current membership in the host's private lobby, so a copied address cannot bypass the invitation. Treat every full address as a secret; reopening the world creates a new token.
- Steam guests are proxied into Minecraft through `127.0.0.1`. Server-side IP bans and mods that assign special trust to localhost cannot distinguish individual Steam guests.
- App ID 480 is Steam's shared development/test application. It is not a private production namespace and must not be presented as one.
- The first implementation uses [steamworks4j](https://github.com/code-disaster/steamworks4j) and the legacy Steamworks `ISteamNetworking` P2P API. Valve has deprecated this API in favor of Steam Networking Sockets; migration is planned for a later transport revision.

## Building

JDK 21 is required to run the pinned Loom build tooling. The two legacy
artifacts target Java 16 bytecode; the four standard/modern artifacts target
Java 17 bytecode. Newer Minecraft lines run it on their required newer Java
runtime.

```powershell
.\gradlew.bat clean releaseJars
```

On Linux:

```bash
./gradlew clean releaseJars
```

`releaseJars` runs both common test suites, produces four variants in the main
platform projects' `build/libs` directories and two in
`legacy/*/build/libs`. It also audits classfile levels, metadata, notices, and
bundled native libraries. It deliberately does not publish or upload them. The
Windows wrapper uses a temporary free drive letter
while Gradle is running, then removes it, so checkouts in non-ASCII paths are
supported. This project disables the persistent Gradle daemon to ensure that no
process retains the temporary mapping after a build.

The tracked root `steam_appid.txt` contains only `480` for local development and is not packed into the mod JAR. At first Steam initialization the mod creates the same file in Minecraft's working/game directory if it is missing; that file persists after exit. The mod refuses to overwrite a different App ID and verifies after initialization that Steam actually assigned App ID 480. If you stop using all Steam-integrated mods, you may remove the generated file while Minecraft is closed. Runtime copies in nested game directories are ignored by Git.

## Publishing the source on GitHub

Publishing this source repository is separate from distributing compiled JARs. Replace the placeholder Git identity first, review the staged snapshot, and then create the public repository with [GitHub CLI](https://cli.github.com/):

```powershell
git config user.name "Kamilchik"
git config user.email "YOUR_GITHUB_NOREPLY_EMAIL"
git add -A
git diff --cached --check
git status --short
git commit -m "Initial public source release"
git branch -M main
gh auth login
gh repo create e4steam --public --source . --remote origin --push
```

If `e4steam` was already created on GitHub, replace the last command with:

```powershell
git remote add origin https://github.com/YOUR_NAME/e4steam.git
git push -u origin main
```

These commands publish source only; they do not create a GitHub Release or upload JAR files. After creating the repository, enable **Settings → Security → Private vulnerability reporting** so reports described in `SECURITY.md` have a private destination.

## Project status

This App ID 480 alpha is intended for development and private testing. The
source repository may be public, but the current compatibility matrix is not
equivalent to a tested binary release. Do not present it as a Valve-approved
production release. Before publishing a binary publicly, the publisher must
complete the per-version smoke-test matrix, personally satisfy the Steamworks
SDK/redistributable terms, and obtain Valve confirmation for the intended
public App ID 480 use, or move to an approved project App ID. A later
production transport should also migrate away from the deprecated networking
interface.

See the [release checklist](RELEASING.md) for GitHub and Modrinth packaging details.

## Author, license, and attribution

e4steam is authored and maintained by **Kamilchik**. It is based on the original e4mc project by Skye and its contributors. This fork remains available under the [MIT License](LICENSE); bundled dependency and Valve redistributable terms are listed in [Third-party notices](THIRD_PARTY_NOTICES.md).

---

## По-русски

e4steam — экспериментальный отдельный форк e4mc, который передаёт подключение к локальному миру Minecraft через Steam P2P. Автор и текущий сопровождающий проекта — **Kamilchik**. В меню **«Открыть для сети»** можно выбрать обычную локальную сеть, доступ для друзей Steam или доступ только по приглашению. В двух Steam-режимах создаётся лобби, а в режиме друзей короткий адрес вида `s-...steam` остаётся запасным способом подключения.

Мод должен быть установлен у обоих игроков, а Steam должен быть запущен и авторизован на обоих компьютерах. Проброс портов и белый IP не нужны: Steam использует прямой P2P-маршрут либо ретранслятор Valve. Для Shift+Tab добавьте Prism Launcher в Steam как стороннюю игру и запускайте Prism из библиотеки Steam. Гость открывает **«Сетевая игра»** — ожидание приглашений включается автоматически, а кнопка друзей открывает оверлей. Хост приглашает через Shift+Tab, меню паузы или `/e4steam invite`.

Каждое подключение проверяет случайный 128-битный токен и прямую дружбу гостя с хостом в Steam. В режиме друзей статус **«Присоединиться к игре»** видят друзья, а скопированный адрес работает как запасной способ. Закрытый режим дополнительно требует участия гостя в текущем приватном лобби, поэтому пересланный адрес не обходит приглашение. Полный адрес всё равно следует считать секретным. Форк использует собственный идентификатор мода `e4steam`. Обычный LAN-порт Minecraft по-прежнему доступен в вашей локальной сети согласно правилам фаервола.

В исходниках `0.2.0-alpha.1` собираются шесть вариантов: отдельные
экспериментальные Fabric/Quilt Legacy 17 для 1.17.x и Forge Legacy 17 для
1.17.1–1.18.1, Fabric/Quilt для 1.18–1.21.11, Fabric/Quilt Modern для
26.1–26.2, Forge для 1.18.2–1.20.2 и NeoForge для 1.20.2–26.2. Для двух
Legacy вариантов достаточно Java 16. Переходная 1.20.2 поддерживает как Forge,
так и NeoForge; после неё используется NeoForge. Пока полноценно проверена
только базовая Minecraft 1.20.2 на Fabric, Forge и NeoForge: остальные
сочетания должны пройти отдельный запуск в чистой инстанции до публикации бинарников.
Поддерживаются Windows x64 и Linux x64; выделенные серверы пока не
поддерживаются. Настоящую игру Spacewar запускать не требуется. Steamworks
с App ID 480 включается только во время хостинга, ожидания приглашения,
подключения или игры и автоматически выключается после выхода. В режиме
обычной локальной сети он вообще не запускается.


# e-steam
