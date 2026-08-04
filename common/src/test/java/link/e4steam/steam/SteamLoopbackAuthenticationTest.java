package link.e4steam.steam;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SteamLoopbackAuthenticationTest {
    @Test
    void acceptsResolvedIpv4AndIpv6LoopbackPorts() throws Exception {
        assertEquals(32123, SteamLoopbackAuthentication.loopbackPort(
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 32123)
        ));
        assertEquals(32124, SteamLoopbackAuthentication.loopbackPort(
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{
                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
                }), 32124)
        ));
    }

    @Test
    void rejectsExternalUnresolvedAndUnknownAddresses() throws Exception {
        assertEquals(-1, SteamLoopbackAuthentication.loopbackPort(
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 0, 0, 2}), 32123)
        ));
        assertEquals(-1, SteamLoopbackAuthentication.loopbackPort(
                InetSocketAddress.createUnresolved("127.0.0.1", 32123)
        ));
        assertEquals(-1, SteamLoopbackAuthentication.loopbackPort(new SocketAddress() {
        }));
        assertEquals(-1, SteamLoopbackAuthentication.loopbackPort(null));
    }
}
