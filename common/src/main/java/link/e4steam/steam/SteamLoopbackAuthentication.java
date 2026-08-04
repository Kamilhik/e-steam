package link.e4steam.steam;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Validates that a Minecraft connection really came through local Steam bridge TCP. */
final class SteamLoopbackAuthentication {
    private SteamLoopbackAuthentication() {
    }

    static int loopbackPort(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inetAddress)
                || inetAddress.isUnresolved()
                || inetAddress.getAddress() == null
                || !inetAddress.getAddress().isLoopbackAddress()) {
            return -1;
        }
        int port = inetAddress.getPort();
        return port > 0 && port <= 65535 ? port : -1;
    }
}
