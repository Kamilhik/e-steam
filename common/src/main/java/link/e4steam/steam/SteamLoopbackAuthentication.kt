package link.e4steam.steam

import java.net.InetSocketAddress
import java.net.SocketAddress

/** Validates that a Minecraft connection really came through local Steam bridge TCP. */
class SteamLoopbackAuthentication private constructor() {
    companion object {
        @JvmStatic
        fun loopbackPort(address: SocketAddress?): Int {
            val inet = address as? InetSocketAddress ?: return -1
            if (inet.isUnresolved || inet.address == null || !inet.address.isLoopbackAddress) {
                return -1
            }
            val port = inet.port
            return if (port > 0 && port <= 65535) port else -1
        }
    }
}
