# Security policy

## Supported versions

Only the latest `0.x` prerelease is supported while the Steam transport is experimental.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository once it is enabled. Do not publish working invite addresses, Steam session details, account identifiers, or logs containing private data in a public issue.

Include the mod version, loader, Minecraft version, operating system, whether the failure occurred as host or guest, and the smallest reproduction you can provide. The `/e4steam doctor` output intentionally omits the invite token; review diagnostics before sharing them.

## Invite handling

The full compact `s-...steam` address (and the accepted long `e4steam-...steam` fallback form) contains a random 128-bit bearer token. Both Steam modes also require the remote account to be a direct friend of the host. Friends-only mode advertises the lobby and permits the copied address as a fallback. Invitation-only mode additionally requires the remote account to remain a current member of the host's private lobby, so possession of a copied address alone is insufficient. Use `/e4steam stop` or reopen the world to invalidate the current token.
