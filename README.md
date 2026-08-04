<div align="center">

<img src="common/src/main/resources/assets/e4steam_minecraft/icon.png" width="180" alt="e4steam logo">

# e4steam

### Play Minecraft with friends through Steam

🇬🇧 **English** · 🇷🇺 **Русский**

<a href="https://discord.gg/k2EsPGQfMu" title="Join the Discord server"><img src="https://img.shields.io/badge/-%20-5865F2?style=for-the-badge&logo=discord&logoColor=white" height="42" alt="Discord"></a>
<a href="https://t.me/Kamilchikm" title="Telegram channel"><img src="https://img.shields.io/badge/-%20-26A5E4?style=for-the-badge&logo=telegram&logoColor=white" height="42" alt="Telegram"></a>
<a href="https://dalink.to/kamilchik1231" title="All project links on DAlink"><img src="https://img.shields.io/badge/DAlink-Links-6C5CE7?style=for-the-badge" height="42" alt="DAlink"></a>
<a href="https://www.curseforge.com/minecraft/mc-mods/e4steam" title="Download on CurseForge"><img src="https://img.shields.io/badge/-%20-F16436?style=for-the-badge&logo=curseforge&logoColor=white" height="42" alt="CurseForge"></a>
<a href="https://github.com/Kamilhik/e4steam" title="GitHub repository"><img src="https://img.shields.io/badge/-%20-181717?style=for-the-badge&logo=github&logoColor=white" height="42" alt="GitHub"></a>
<a href="https://modrinth.com/project/SqqdJF90" title="View on Modrinth"><img src="https://img.shields.io/badge/-%20-00AF5C?style=for-the-badge&logo=modrinth&logoColor=white" height="42" alt="Modrinth"></a>
<a href="https://youtu.be/KJ1W_eJ2VK4" title="Watch the demonstration"><img src="https://img.shields.io/badge/-%20-FF0000?style=for-the-badge&logo=youtube&logoColor=white" height="42" alt="YouTube"></a>

[![Version](https://img.shields.io/github/v/release/Kamilhik/e4steam?display_name=tag&sort=semver&style=flat-square)](https://github.com/Kamilhik/e4steam/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/Kamilhik/e4steam/build.yml?branch=main&label=build&style=flat-square)](https://github.com/Kamilhik/e4steam/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-MIT-2ea44f?style=flat-square)](LICENSE)

**🇷🇺 Русская версия находится ниже — [открыть](#русская-версия)**

</div>

> [!IMPORTANT]
> **e4steam 0.2.1 is the current stable release.** Windows x64 is the primary
> supported platform. Linux x64 is experimental. Dedicated servers and macOS
> are not supported. e4steam permanently uses the shared Steam test App ID 480
> (Spacewar), so unrelated App ID 480 traffic is possible and is filtered.

e4steam opens a Minecraft singleplayer world to Steam friends without port
forwarding or a public IP. Both players need the mod and a signed-in Steam
client. Minecraft TCP traffic and supported voice-chat UDP traffic travel over
Steam P2P or Valve relays.

## Which file should I download?

| Minecraft | Loader | File name contains | Extra dependency |
| --- | --- | --- | --- |
| 1.17–1.18.2 | Fabric/Quilt | `fabric-quilt-mc1.17-1.18.2` | Fabric API |
| 1.17.1–1.18.1 | Forge | `forge-mc1.17.1-1.18.1` | None |
| 1.19–1.21.x | Fabric/Quilt | `fabric-quilt-mc1.19-1.21.11` | Fabric API |
| 1.18.2–1.20.2 | Forge | `forge-mc1.18.2-1.20.2` | None |
| 1.20.2–1.21.x | NeoForge | `neoforge-mc1.20.2-26.2` | None |
| 26.1–26.2 | Fabric/Quilt or NeoForge | file containing `mc26.1-26.2` | Fabric API only for Fabric/Quilt |

Each listed JAR already contains both Windows x64 and Linux x64 Steam native
libraries. Download one file for your Minecraft version and loader; there are
no separate Windows and Linux builds.

Declared ranges are broader than the manually tested matrix. Check
[COMPATIBILITY.md](COMPATIBILITY.md); unverified combinations are experimental.

## Installation

1. Install the loader matching your Minecraft version.
2. Download the matching e4steam JAR from [GitHub Releases](https://github.com/Kamilhik/e4steam/releases), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/e4steam), or [Modrinth](https://modrinth.com/mod/e4steam).
3. Put the JAR in the instance's `mods` folder. Fabric and Quilt also require Fabric API.
4. Install the same e4steam release on every player's computer.
5. Start Steam and sign in before launching Minecraft.

## Steam Overlay setup

1. In Steam, select **Games → Add a Non-Steam Game** and add your Minecraft launcher.
2. Open the shortcut properties and enable **Steam Overlay while in-game**.
3. Start the launcher from that Steam shortcut, then start Minecraft.
4. In a world, press **Shift + Tab** to verify that the overlay opens.

## Playing

1. Open a singleplayer world.
2. Select **Open to LAN → Steam friends** or **Invitation only**.
3. Press **Invite friends** and send the invitation in the Steam overlay.
4. Your friend accepts the invite and confirms joining in Minecraft.

Simple Voice Chat is detected automatically. Plasmo Voice is supported when it
shares Minecraft's port. Another UDP mod can use the `voiceChatPort` setting.

## If an invitation does not arrive

- Confirm both players are Steam friends, online, and using the same e4steam release.
- Confirm both use the same Minecraft version and compatible loaders.
- Start the launcher through Steam and check **Shift + Tab**.
- Ask the host to close and reopen the Steam connection, then send a new invite.
- For a friends-only lobby, copy the green e4steam address as a fallback.
- Restart Steam if Spacewar presence or the overlay is stuck.

## Known limitations

- App ID 480 is a shared test namespace and is not exclusive to e4steam.
- Integrated singleplayer worlds only; dedicated servers are unsupported.
- Windows x64 is primary; Linux x64 is experimental; macOS and 32-bit systems are unsupported.
- Both players need Steam, e4steam, matching Minecraft versions, and compatible loaders.
- Some declared version/loader combinations are experimental until manually smoke-tested.
- The shared world uses Minecraft's standard limit of 8 players including the host.

## Demo

[![e4steam video demonstration](https://img.youtube.com/vi/KJ1W_eJ2VK4/maxresdefault.jpg)](https://youtu.be/KJ1W_eJ2VK4)

![Open Server](https://cdn.modrinth.com/data/cached_images/d6b56bd5c9285ed7c0a56af61a1198657a334028.gif)

![Close Server](https://cdn.modrinth.com/data/cached_images/6a93fb8208dcdeea1336607e1d75846af7e31cd7.gif)

---

<details id="русская-версия" open>
<summary><h2>🇷🇺 Русская версия</h2></summary>

> [!IMPORTANT]
> **e4steam 0.2.1 — текущий стабильный релиз.** Основная поддерживаемая
> платформа — Windows x64. Linux x64 пока экспериментальный. Выделенные серверы
> и macOS не поддерживаются. Мод навсегда использует общий тестовый Steam App ID
> 480 (Spacewar), поэтому посторонний трафик App ID 480 возможен и фильтруется.

e4steam позволяет открыть одиночный мир Minecraft друзьям из Steam без проброса
портов и белого IP. Мод и запущенный Steam нужны у всех игроков. TCP-трафик
Minecraft и UDP-трафик поддерживаемых голосовых модов передаются через Steam P2P
или ретрансляторы Valve.

## Какой файл скачивать

| Minecraft | Загрузчик | В названии файла | Дополнительно |
| --- | --- | --- | --- |
| 1.17–1.18.2 | Fabric/Quilt | `fabric-quilt-mc1.17-1.18.2` | Fabric API |
| 1.17.1–1.18.1 | Forge | `forge-mc1.17.1-1.18.1` | Ничего |
| 1.19–1.21.x | Fabric/Quilt | `fabric-quilt-mc1.19-1.21.11` | Fabric API |
| 1.18.2–1.20.2 | Forge | `forge-mc1.18.2-1.20.2` | Ничего |
| 1.20.2–1.21.x | NeoForge | `neoforge-mc1.20.2-26.2` | Ничего |
| 26.1–26.2 | Fabric/Quilt или NeoForge | файл с `mc26.1-26.2` | Fabric API только для Fabric/Quilt |

Каждый указанный JAR уже содержит библиотеки Steam для Windows x64 и Linux x64.
Для своей версии Minecraft и загрузчика нужно скачать один файл — отдельных
сборок для Windows и Linux нет.

Заявленный диапазон шире проверенной матрицы. Смотрите
[COMPATIBILITY.md](COMPATIBILITY.md): непроверенные сочетания считаются экспериментальными.

## Как установить мод

1. Установите загрузчик, подходящий вашей версии Minecraft.
2. Скачайте нужный JAR с [GitHub Releases](https://github.com/Kamilhik/e4steam/releases), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/e4steam) или [Modrinth](https://modrinth.com/mod/e4steam).
3. Поместите JAR в папку `mods`. Для Fabric и Quilt также установите Fabric API.
4. Установите одинаковый релиз e4steam всем игрокам.
5. Запустите Steam и войдите в аккаунт до запуска Minecraft.

## Как настроить оверлей Steam

1. В Steam выберите **Игры → Добавить стороннюю игру** и добавьте свой лаунчер Minecraft.
2. В свойствах ярлыка включите **Оверлей Steam в игре**.
3. Запускайте лаунчер из этого ярлыка Steam, затем запускайте Minecraft.
4. В мире нажмите **Shift + Tab** и убедитесь, что оверлей открывается.

## Как играть

1. Откройте одиночный мир.
2. Выберите **Открыть для сети → Для друзей Steam** или **Только по приглашению**.
3. Нажмите **Пригласить друзей** и отправьте приглашение через оверлей Steam.
4. Друг принимает приглашение и подтверждает вход в Minecraft.

Simple Voice Chat определяется автоматически. Plasmo Voice поддерживается,
когда использует порт Minecraft. Для другого UDP-мода укажите `voiceChatPort`.

## Если приглашение не приходит

- Проверьте, что вы друзья в Steam, оба онлайн и используете одинаковый релиз e4steam.
- Проверьте совпадение версии Minecraft и совместимость загрузчиков.
- Запустите лаунчер через Steam и проверьте **Shift + Tab**.
- Закройте соединение, откройте его заново и отправьте новое приглашение.
- В режиме для друзей можно скопировать зелёный адрес как запасной вариант.
- Перезапустите Steam, если статус Spacewar или оверлей завис.

## Известные ограничения

- App ID 480 — общий тестовый идентификатор, не принадлежащий e4steam.
- Работают только одиночные миры; выделенные серверы не поддерживаются.
- Windows x64 — основная платформа, Linux x64 экспериментальный; macOS и 32-bit не поддерживаются.
- Всем нужны Steam, e4steam, одинаковая версия Minecraft и совместимые загрузчики.
- Непроверенные сочетания версий и загрузчиков считаются экспериментальными.
- Открытый мир использует стандартный лимит Minecraft: 8 игроков вместе с хостом.

</details>

---

Created and maintained by **Kamilchik**. e4steam is an unofficial fork of
[e4mc](https://github.com/vgskye/e4mc-minecraft-architectury), distributed under
the [MIT License](LICENSE), and is not affiliated with Valve, Mojang, or Microsoft.
