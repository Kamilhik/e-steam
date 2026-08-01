# Security policy

## Supported versions

| Version | Supported |
| --- | --- |
| 0.2.x stable | ✅ Yes |
| Older 0.x and alpha builds | ❌ No |

Security fixes are provided for the latest stable 0.2.x release. Dedicated
servers, macOS, and 32-bit operating systems are not supported.

## Reporting a vulnerability

Use GitHub private vulnerability reporting. Do not publish working invite
addresses, Steam session details, account identifiers, or logs containing
private data in a public issue.

Include the e4steam version, loader, Minecraft version, operating system,
whether the failure occurred as host or guest, and the smallest reproduction
you can provide. The `/e4steam doctor` output intentionally omits the invite
token; review diagnostics before sharing them.

## Invite handling

The compact `s-...steam` address and accepted long fallback contain a random
128-bit bearer token. Both access modes also require the remote Steam account
to be a direct friend of the host. Invite-only mode additionally requires
current membership in the host's private lobby. Use `/e4steam stop` or reopen
the world to invalidate the current token.
