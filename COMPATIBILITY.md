# e4steam compatibility

Build compatibility and multiplayer feature compatibility are tracked
separately. Reaching the Minecraft main menu proves that the loader can load
e4steam; it does not by itself prove Steam invitations or voice chat.

Legend: ✅ verified · ⏳ not yet manually verified · — unsupported.

## Windows client launch matrix

On 2026-08-01, 99 clean launcher test instances reached Minecraft's main-menu
initialization with e4steam 0.2.0 installed. Fabric and Quilt instances also
contained the matching Fabric API version.

| Loader | Minecraft versions launched | Result |
| --- | --- | --- |
| Fabric | 1.17–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 | 33/33 ✅ |
| Quilt | 1.17–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 | 33/33 ✅ |
| Forge | 1.17.1–1.20.2 | 12/12 ✅ |
| NeoForge | 1.20.2–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2 | 21/21 ✅ |

The exact automated launch records are generated locally in
`build/client-compatibility.json`. Minecraft 26.x uses the dedicated modern
Fabric/Quilt artifact.

## Confirmed multiplayer checks

| Minecraft | Loader | Windows x64 | Linux x64 | Steam invite | Voice chat |
| --- | --- | --- | --- | --- | --- |
| 1.20.2 | Fabric | ✅ | ✅ | ✅ | ✅ |
| 1.20.2 | Forge | ✅ | ⏳ | ✅ | ✅ |
| 1.20.2 | NeoForge | ✅ | ⏳ | ✅ | ✅ |
| 1.21.1 | NeoForge | ✅ client launch | ⏳ | ⏳ | ⏳ |

Only cells marked ✅ have completed that specific check. Other combinations
remain experimental for host/guest networking even when their client launch is
confirmed above.

## Full host/guest procedure

1. Launch clean clients on both computers.
2. Start, stop, and restart sharing.
3. Test friends-only and invitation-only lobbies.
4. Send and accept a Shift+Tab invitation.
5. Join, disconnect, and connect to a regular server; Spacewar must close.
6. Test Simple Voice Chat or Plasmo Voice in both directions.
7. Close the world while a guest is connecting.
8. Disconnect Steam and verify recovery.

Dedicated servers, macOS, and 32-bit operating systems are unsupported.
