# Changelog

All notable changes to e4steam are documented here. Version numbers below
belong to this fork and are independent of upstream e4mc releases.

## Unreleased

### English

- Fixed a `NoSuchFieldError: Minecraft.screen` crash when accepting a Steam
  invite or opening the stop confirmation on Minecraft 26.2. The current screen
  is now accessed through the version-compatibility layer, which uses
  `Gui#screen()`/`Gui#setScreen()` on 26.2 and the classic `Minecraft.screen`
  field everywhere else.
- Added experimental Fabric support for Minecraft 26.3 Snapshot 7. The modern
  artifact recognizes snapshot version names, resolves the renamed client
  disconnect method, and places the Steam access selector inside the new
  scrollable `WorldOptionsScreen` multiplayer layout. Its metadata retains
  26.1–26.2 support and accepts Fabric Loader's `26.3-alpha.7` identifier.

### Русский

- Исправлен вылет `NoSuchFieldError: Minecraft.screen` при принятии
  Steam-приглашения и открытии подтверждения остановки на Minecraft 26.2.
  Текущий экран теперь читается через слой версионной совместимости:
  на 26.2 используется `Gui#screen()`/`Gui#setScreen()`, на остальных версиях —
  прежнее поле `Minecraft.screen`.
- Добавлена экспериментальная поддержка Fabric для Minecraft 26.3 Snapshot 7.
  Современный JAR распознаёт имена снапшотов, учитывает переименованный метод
  отключения клиента и размещает выбор доступа Steam внутри новой
  прокручиваемой сетевой секции `WorldOptionsScreen`. Метаданные сохраняют
  поддержку 26.1–26.2 и принимают внутреннюю версию Fabric `26.3-alpha.7`.

## 0.2.2 - 2026-08-05

### English

- Added offline-launcher profile support for Steam guests. Mojang session
  authentication is bypassed only for an already authenticated Steam bridge;
  Minecraft keeps its normal offline profile through login and configuration.
- Replaced the deprecated `ISteamNetworking` transport with
  `ISteamNetworkingMessages`, the packet-oriented API built on Steam
  Networking Sockets.
- Raised the wire and lobby protocol version to 3 so legacy-transport clients
  fail compatibility checks instead of attempting an incompatible session.
- Added periodic acceptance for authenticated lobby peers and Steam Networking
  Sockets diagnostics to make session establishment reliable even when the
  asynchronous session-request callback is delayed.
- Restored the e4steam access selector on Minecraft 26.x, where the old
  `ShareToLanScreen` was replaced by `MultiplayerOptionsScreen`.
- Steam lobby creation now retries temporary network failures, improving
  startup on slow and VPN-routed connections.
- Fixed immediate guest disconnects and infinite falling during world loading
  by preserving reliable packet order, pacing bursts, and expanding the
  bounded localhost buffer.
- Restored the native Steam friends invitation dialog.
- Fixed missing Steamworks compatibility classes on Forge 1.18.1.
- Fixed the LAN configuration crash and restored the e4steam access selector
  on Fabric and Quilt 1.21.11.
- Removed the temporary movement lock after joining a world.

### Русский

- Добавлена поддержка офлайн-профилей лаунчеров для гостей Steam. Проверка
  сессии Mojang отключается только для уже авторизованного Steam bridge, а
  профиль Minecraft сохраняется штатным при переходе login/configuration.
- Устаревший транспорт `ISteamNetworking` заменён на
  `ISteamNetworkingMessages` — пакетный API поверх Steam Networking Sockets.
- Версия сетевого и lobby-протокола повышена до 3, чтобы клиенты со старым
  транспортом отклонялись проверкой совместимости.
- Добавлены периодическое принятие сессий проверенных участников lobby и
  диагностика Steam Networking Sockets на случай задержки callback-запроса.
- Возвращён выбор режима доступа e4steam на Minecraft 26.x, где старый
  `ShareToLanScreen` заменён новым `MultiplayerOptionsScreen`.
- Создание лобби Steam теперь повторяется при временных сетевых ошибках, что
  повышает надёжность запуска через медленные соединения и VPN.
- Исправлены мгновенные отключения гостей и бесконечное падение при загрузке
  мира: надёжные пакеты сохраняют порядок, обрабатываются равномерными
  порциями, а ограниченный локальный буфер увеличен.
- Возвращено нативное окно приглашения друзей Steam.
- Исправлены отсутствующие классы совместимости Steamworks на Forge 1.18.1.
- Исправлен вылет меню открытия мира для сети и возвращён выбор режима доступа
  e4steam на Fabric и Quilt 1.21.11.
- Убрана временная блокировка движения после подключения к миру.

## 0.2.1 - 2026-08-03

### English

- Removed the experimental 32-player expansion and restored Minecraft's
  standard integrated-world limit of 8 players including the host.
- Fixed the endless world/terrain loading screen on Minecraft 26.2.
- Rechecked world loading and Steam LAN sharing on Fabric 26.2, Quilt 1.20.2,
  Forge 1.20.2, and NeoForge 1.21.1.

### Русский

- Удалено экспериментальное расширение до 32 игроков и возвращён стандартный
  лимит Minecraft: 8 игроков вместе с хостом.
- Исправлена бесконечная загрузка мира/территории на Minecraft 26.2.
- Повторно проверены загрузка мира и открытие Steam-LAN на Fabric 26.2,
  Quilt 1.20.2, Forge 1.20.2 и NeoForge 1.21.1.

## 0.2.0 - 2026-08-01

### English

- Verified 99 Windows client launches across Fabric, Quilt, Forge, and NeoForge.
- Fixed modern Fabric API metadata for Minecraft 26.x.
- Fixed Forge 1.18.2 startup by removing an invalid inherited `PauseScreen.tick` injection.
- Spacewar now closes when Minecraft connects to a regular server.
- Replaced launcher-specific wording with generic Minecraft launcher guidance.
- Promoted e4steam to its first stable public release while keeping Steam App
  ID 480 as the permanent transport namespace.
- Removed obsolete alpha releases and normalized the stable release metadata.
- Documented installation, Steam Overlay setup, troubleshooting, supported
  files, platform limits, and the verified compatibility matrix.
- Added testable Steam lifecycle boundaries and regression coverage for
  restart, cancellation, invalid or expired invitations, unknown peers, queue
  overflow, Steam loss, world shutdown, lobby loss, and concurrent guests.
- Split Steam runtime responsibilities into lifecycle, packet transport,
  bridge registry, outbound queue, and lobby management components without
  changing the wire protocol.
- Updated the release, security, contribution, and bug-report documentation
  for the stable 0.2.0 line.
- Separated client-launch evidence from manual host/guest multiplayer evidence
  and added a native Windows build job to GitHub Actions.
- Documented that every loader/version JAR is shared by Windows x64 and Linux
  x64 and bundles native Steam libraries for both systems.

### Русский

- Проверен запуск 99 клиентов Windows на Fabric, Quilt, Forge и NeoForge.
- Исправлена зависимость Fabric API для Minecraft 26.x.
- Исправлен запуск Forge 1.18.2: удалено некорректное внедрение в унаследованный
  метод `PauseScreen.tick`.
- Spacewar теперь закрывается при подключении Minecraft к обычному серверу.
- Убраны упоминания конкретного лаунчера; подсказки подходят для любого
  лаунчера Minecraft.
- e4steam выпущен как первый стабильный релиз. Steam App ID 480 остаётся
  постоянным транспортным идентификатором проекта.
- Удалены устаревшие альфа-релизы и приведены в порядок данные стабильного релиза.
- Добавлены инструкции по установке, настройке оверлея Steam, устранению проблем,
  выбору файла и ограничениям платформ.
- Добавлены тесты перезапуска Steam, отмены подключения, неверных и просроченных
  приглашений, незнакомых пользователей, переполнения очереди, отключения Steam,
  закрытия мира, потери лобби и одновременных гостей.
- SteamRuntime разделён на компоненты жизненного цикла, транспорта пакетов,
  реестра соединений, очереди отправки и управления лобби без изменения протокола.
- Документы выпуска, безопасности, участия в разработке и сообщения об ошибках
  приведены к состоянию стабильной ветки 0.2.0.
- Проверки запуска клиента отделены от ручных host/guest-проверок, а в GitHub
  Actions добавлена отдельная сборка на Windows.
- Уточнено, что один JAR для выбранной версии и загрузчика используется на
  Windows x64 и Linux x64 и содержит Steam-библиотеки для обеих систем.

## 0.2.0-alpha.4 - 2026-08-01

- Added an activity-scoped UDP tunnel alongside the existing Minecraft TCP
  bridge, enabling voice chat and other UDP-based mods.
- Added automatic runtime port discovery for Simple Voice Chat and automatic
  Minecraft-port mapping for Plasmo Voice. The selected UDP endpoint is sent
  to guests during the Steam handshake.
- Voice datagrams use Steam's unreliable no-delay delivery and a separate
  bounded queue so voice traffic cannot starve the Minecraft connection.
- Added local UDP proxy tests, protocol validation, a configurable fallback
  `voiceChatPort`, and six-artifact UDP audits.
- Raised the e4steam wire and lobby protocol version to 2; both players must
  use the same `0.2.0-alpha.4` build.

## 0.2.0-alpha.3 - 2026-08-01

- Increased shared integrated-world capacity to 32 players total, including
  the host, and aligned the Steam lobby with the same limit.
- Added a shared, tested session-limit definition used by both Minecraft and
  the Steam transport.

## 0.2.0-alpha.2 - 2026-07-31

- Removed the direct pre-1.21 `GenericDirtMessageScreen` link and select the
  renamed 1.21+ `GenericMessageScreen` through the compatibility boundary.
- Corrected Fabric compatibility: the Command API v1 build now covers
  Minecraft 1.17–1.18.2, while the Command API v2 build starts at 1.19.
- Added an artifact audit that rejects a direct link to the renamed screen.

## 0.2.0-alpha.1 - 2026-07-31

- Renamed the separate project, mod ID, Java namespace, commands, and release
  artifacts to **e4steam**.
- Added public-repository contribution guidance, issue/PR templates, and
  Dependabot configuration.
- Included the complete Apache License 2.0 text required by the shaded Kaleido
  Config dependency in the third-party notices packaged with the mod.
- Added six release variants: separate experimental Fabric/Quilt and Forge
  legacy artifacts for 1.17.x and 1.17.1–1.18.1 respectively, Fabric/Quilt for
  1.18–1.21.11, Fabric/Quilt Modern for 26.1–26.2, Forge for
  1.18.2–1.20.2, and NeoForge for 1.20.2–26.2. Wider compatibility remains
  gated on per-version smoke tests.
- Shortened new connection addresses to the
  `s-<SteamID-in-base36>-<token-in-base36>.steam` form.
- Added runtime Minecraft-version discovery and compatibility adapters for
  buttons, tooltips, multiplayer connection, and world disconnect across the
  declared version families.

## 0.1.0-alpha.3 - 2026-07-30

- Added Steam friends-only and invitation-only lobby modes to Minecraft's Open to LAN screen.
- Added Shift+Tab invitation support through Steam lobbies and rich presence.
- Added a Steam friends button to Multiplayer and an invitation button to the pause menu.
- Added `/e4steam invite` and a clickable invitation action in the host chat message.
- Added a random 128-bit invitation check and direct host friendship check for every incoming bridge; invitation-only sessions also require current private-lobby membership.
- Made Steamworks restartable and activity-scoped: App ID 480 is inactive during ordinary Minecraft use and shuts down after hosting, waiting, or playing ends.
- Kept a local-only LAN mode that never initializes Steamworks.

## 0.1.0-alpha.2 - 2026-07-30

- Fixed Steam native library loading from isolated NeoForge, Forge, and Fabric mod class loaders.
- Added verified extraction of the bundled Windows/Linux x64 Steam libraries to a content-addressed local cache.
- Added detailed native loading errors instead of the previous generic initialization message.

## 0.1.0-alpha.1 - 2026-07-30

- Replaced the original public relay transport with a Steam P2P bridge.
- Added development initialization through App ID 480 (Spacewar) without launching the Spacewar game.
- Added authenticated host addresses for direct Steam connections.
- Added direct Steam P2P transport with Valve relay fallback.
- Required the mod and a signed-in Steam client on both host and guest.
- Targeted Windows x64 and Linux x64 for the first release.
- Limited the first release to Minecraft's integrated single-player server; dedicated servers are not yet supported.
- Documented that the legacy `ISteamNetworking` API is deprecated and should be replaced by Steam Networking Sockets in a future release.
- Added English and Russian in-game messages for the Steam-based flow.
- Added protocol tests, bounded queues, generation-safe terminal frames, graceful half-close handling, Steam send-queue draining, and redacted invite logging.
- Added a runtime check that refuses to continue unless Steam actually initializes the process as App ID 480.

## Upstream history

This repository is derived from e4mc by skyevg and contributors. The original project's release history predates this separate Steam fork and is intentionally not reused as this project's changelog.
