package link.e4steam.steam;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteamUdpBridgeTest {
    @Test
    void clientProxyReturnsSteamDatagramsToTheLocalVoiceClient() throws Exception {
        SteamConnectionBridge owner = ownerBridge(71);
        SteamUdpBridge bridge = SteamUdpBridge.client(SteamRuntime.get(), owner, 0);
        try (DatagramSocket voiceClient = new DatagramSocket(new InetSocketAddress(loopback(), 0))) {
            voiceClient.setSoTimeout(2_000);
            bridge.start();

            send(voiceClient, bridge.localPort(), "voice-request".getBytes());
            long deadline = System.currentTimeMillis() + 2_000;
            while (!bridge.hasLocalClient() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(bridge.hasLocalClient());

            byte[] reply = "voice-reply".getBytes();
            bridge.acceptSteamDatagram(reply);
            assertArrayEquals(reply, receive(voiceClient));
        } finally {
            bridge.close();
            owner.close(false);
        }
    }

    @Test
    void hostProxyDeliversSteamDatagramsToTheVoiceServer() throws Exception {
        SteamConnectionBridge owner = ownerBridge(72);
        try (DatagramSocket voiceServer = new DatagramSocket(new InetSocketAddress(loopback(), 0))) {
            voiceServer.setSoTimeout(2_000);
            SteamUdpBridge bridge = SteamUdpBridge.host(
                    SteamRuntime.get(),
                    owner,
                    voiceServer.getLocalPort()
            );
            try {
                byte[] request = "voice-request".getBytes();
                bridge.acceptSteamDatagram(request);
                assertArrayEquals(request, receive(voiceServer));
            } finally {
                bridge.close();
            }
        } finally {
            owner.close(false);
        }
    }

    private static SteamConnectionBridge ownerBridge(int connectionId) {
        return new SteamConnectionBridge(
                SteamRuntime.get(),
                76561198000000000L,
                connectionId,
                new Socket(),
                null,
                null
        );
    }

    private static void send(DatagramSocket socket, int port, byte[] payload) throws Exception {
        socket.send(new DatagramPacket(payload, payload.length, new InetSocketAddress(loopback(), port)));
    }

    private static byte[] receive(DatagramSocket socket) throws Exception {
        byte[] buffer = new byte[256];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return java.util.Arrays.copyOfRange(
                packet.getData(),
                packet.getOffset(),
                packet.getOffset() + packet.getLength()
        );
    }

    private static InetAddress loopback() throws Exception {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }
}
