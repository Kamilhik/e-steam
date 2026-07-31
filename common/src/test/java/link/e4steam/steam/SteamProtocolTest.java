package link.e4steam.steam;

import link.e4steam.HexCodec;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SteamProtocolTest {
    @Test
    void encodesAndDecodesOpenDataAndCloseFrames() {
        byte[] token = HexCodec.decode("00112233445566778899aabbccddeeff");
        assertFrame(SteamProtocol.OPEN, 41, token, SteamProtocol.encodeOpen(41, token));

        byte[] data = "minecraft-stream".getBytes();
        assertFrame(SteamProtocol.DATA, -7, data, SteamProtocol.encodeData(-7, data));
        assertFrame(SteamProtocol.FIN, 99, new byte[0], SteamProtocol.encodeFin(99));
        assertFrame(SteamProtocol.RESET, 100, new byte[0], SteamProtocol.encodeReset(100));
    }

    @Test
    void rejectsForeignTrafficAndInvalidConnectionIds() {
        byte[] valid = SteamProtocol.encodeFin(5);
        valid[0] ^= 0x01;
        assertNull(SteamProtocol.decode(ByteBuffer.wrap(valid)));

        byte[] zeroId = SteamProtocol.encodeFin(5);
        ByteBuffer.wrap(zeroId).putInt(SteamProtocol.HEADER_SIZE - Integer.BYTES, 0);
        assertNull(SteamProtocol.decode(ByteBuffer.wrap(zeroId)));
    }

    private static void assertFrame(byte type, int connectionId, byte[] payload, byte[] encoded) {
        SteamProtocol.Frame frame = SteamProtocol.decode(ByteBuffer.wrap(encoded));
        assertEquals(type, frame.type());
        assertEquals(connectionId, frame.connectionId());
        assertArrayEquals(payload, frame.payload());
    }
}
