# Changelog

All notable changes to e4steam are documented here. Version numbers below
belong to this fork and are independent of upstream e4mc releases.

## 0.2.0 - 2026-08-01

- Verified 99 Windows client launches across Fabric, Quilt, Forge, and NeoForge.
- Fixed modern Fabric API metadata for Minecraft 26.x.
- Fixed Forge 1.18.2 startup by removing an invalid inherited `PauseScreen.tick` injection.
- Stop the restartable Spacewar session when Minecraft connects to a regular server.
- Replaced launcher-specific wording with generic Minecraft launcher guidance.

- Promoted e4steam to its first stable public release while keeping Steam App
  ID 480 as the permanent transport namespace.
- Classified every earlier `0.x-alpha` build as a prerelease and corrected the
  Modrinth, CurseForge, and GitHub publishing metadata.
- Documented installation, Steam Overlay setup, troubleshooting, supported
  files, platform limits, and the verified compatibility matrix.
- Added testable Steam lifecycle boundaries and regression coverage for
  restart, cancellation, invalid or expired invitations, unknown peers, queue
  overflow, Steam loss, world shutdown, lobby loss, and concurrent guests.
- Split Steam runtime responsibilities into lifecycle, packet transport,
  bridge registry, outbound queue, and lobby management components without
  changing the wire protocol.

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
