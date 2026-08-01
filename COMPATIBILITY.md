# e4steam compatibility

This page records whether Minecraft reaches its main menu with e4steam loaded.

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

## Platform status

| Platform | Status |
| --- | --- |
| Windows x64 | ✅ Verified with the 99 client launches above |
| Linux x64 | Experimental — not included in this local launch matrix |
| macOS | — Unsupported |
| 32-bit operating systems | — Unsupported |

Dedicated servers are unsupported.
