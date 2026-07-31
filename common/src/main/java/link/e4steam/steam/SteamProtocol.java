package link.e4steam.steam;

import java.nio.ByteBuffer;

final class SteamProtocol {
    static final int MAGIC = 0x45345354; // E4ST
    static final byte VERSION = 1;
    static final byte OPEN = 1;
    static final byte DATA = 2;
    static final byte FIN = 3;
    static final byte RESET = 4;

    static final int DATA_CHUNK_SIZE = 32 * 1024;
    static final int HEADER_SIZE = Integer.BYTES + Byte.BYTES + Byte.BYTES + Short.BYTES + Integer.BYTES;
    static final int MAX_PACKET_SIZE = HEADER_SIZE + DATA_CHUNK_SIZE;
    static final int MAX_ACCEPTED_STEAM_PACKET_SIZE = 1024 * 1024;

    private SteamProtocol() {
    }

    static byte[] encodeOpen(int connectionId, byte[] token) {
        if (token.length != SteamAddress.TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid invite token length");
        }
        ByteBuffer buffer = header(OPEN, connectionId, SteamAddress.TOKEN_LENGTH);
        buffer.put(token);
        return buffer.array();
    }

    static byte[] encodeData(int connectionId, byte[] payload) {
        if (payload.length == 0 || payload.length > DATA_CHUNK_SIZE) {
            throw new IllegalArgumentException("Invalid Steam payload length: " + payload.length);
        }
        ByteBuffer buffer = header(DATA, connectionId, payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    static byte[] encodeFin(int connectionId) {
        return header(FIN, connectionId, 0).array();
    }

    static byte[] encodeReset(int connectionId) {
        return header(RESET, connectionId, 0).array();
    }

    static Frame decode(ByteBuffer source) {
        if (source.remaining() < HEADER_SIZE) {
            return null;
        }
        if (source.getInt() != MAGIC || source.get() != VERSION) {
            return null;
        }

        byte type = source.get();
        source.getShort(); // Reserved for future protocol flags.
        int connectionId = source.getInt();
        int payloadLength = source.remaining();

        if (connectionId == 0) {
            return null;
        }
        if (type == OPEN && payloadLength != SteamAddress.TOKEN_LENGTH) {
            return null;
        }
        if (type == DATA && (payloadLength == 0 || payloadLength > DATA_CHUNK_SIZE)) {
            return null;
        }
        if ((type == FIN || type == RESET) && payloadLength != 0) {
            return null;
        }
        if (type != OPEN && type != DATA && type != FIN && type != RESET) {
            return null;
        }

        byte[] payload = new byte[payloadLength];
        source.get(payload);
        return new Frame(type, connectionId, payload);
    }

    private static ByteBuffer header(byte type, int connectionId, int payloadLength) {
        return ByteBuffer.allocate(HEADER_SIZE + payloadLength)
                .putInt(MAGIC)
                .put(VERSION)
                .put(type)
                .putShort((short) 0)
                .putInt(connectionId);
    }

    record Frame(byte type, int connectionId, byte[] payload) {
    }
}
