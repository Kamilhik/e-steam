package link.e4steam.steam;

import link.e4steam.HexCodec;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SteamProtocolTest {
    @Test
    void encodesAndDecodesOpenDataAndCloseFrames() {
        byte[] token = HexCodec.decode("00112233445566778899aabbccddeeff");
        assertFrame(SteamProtocol.OPEN, 41, token, SteamProtocol.encodeOpen(41, token));
        VoiceChatUdpEndpoint endpoint = VoiceChatUdpEndpoint.fromHandshake(
                24454,
                VoiceChatUdpEndpoint.CLIENT_PORT_SAME_AS_SERVER
        );
        assertFrame(
                SteamProtocol.OPEN_ACK,
                41,
                new byte[]{VoiceChatUdpEndpoint.CLIENT_PORT_SAME_AS_SERVER, 0x5f, (byte) 0x86},
                SteamProtocol.encodeOpenAck(41, endpoint)
        );

        byte[] data = "minecraft-stream".getBytes();
        assertFrame(SteamProtocol.DATA, -7, data, SteamProtocol.encodeData(-7, data));
        byte[] datagram = "voice-datagram".getBytes();
        assertFrame(SteamProtocol.DATAGRAM, 55, datagram, SteamProtocol.encodeDatagram(55, datagram));
        assertFrame(SteamProtocol.FIN, 99, new byte[0], SteamProtocol.encodeFin(99));
        assertFrame(SteamProtocol.RESET, 100, new byte[0], SteamProtocol.encodeReset(100));
    }

    @Test
    void rejectsForeignTrafficAndInvalidConnectionIds() {
        byte[] valid = SteamProtocol.encodeFin(5);
        valid[0] ^= 0x01;
        assertNull(SteamProtocol.decode(ByteBuffer.wrap(valid)));

        byte[] legacyTransport = SteamProtocol.encodeFin(5);
        legacyTransport[Integer.BYTES] = 2;
        assertNull(SteamProtocol.decode(ByteBuffer.wrap(legacyTransport)));

        byte[] zeroId = SteamProtocol.encodeFin(5);
        ByteBuffer.wrap(zeroId).putInt(SteamProtocol.HEADER_SIZE - Integer.BYTES, 0);
        assertNull(SteamProtocol.decode(ByteBuffer.wrap(zeroId)));
    }

    @Test
    void rejectsInvalidDatagramLengths() {
        assertThrows(IllegalArgumentException.class, () -> SteamProtocol.encodeDatagram(1, new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> SteamProtocol.encodeDatagram(1, new byte[SteamProtocol.MAX_DATAGRAM_SIZE + 1])
        );
    }

    private static void assertFrame(byte type, int connectionId, byte[] payload, byte[] encoded) {
        SteamProtocol.Frame frame = SteamProtocol.decode(ByteBuffer.wrap(encoded));
        assertEquals(type, frame.type());
        assertEquals(connectionId, frame.connectionId());
        assertArrayEquals(payload, frame.payload());
    }
}
