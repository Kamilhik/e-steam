package link.e4steam.steam

import java.nio.ByteBuffer

/** Binary framing shared by the TCP bridges and the UDP datagram tunnel. */
class SteamProtocol private constructor() {
    class Frame(type: Byte, connectionId: Int, payload: ByteArray) {
        private val typeValue = type
        private val connectionIdValue = connectionId
        private val payloadValue = payload

        fun type(): Byte = typeValue

        fun connectionId(): Int = connectionIdValue

        fun payload(): ByteArray = payloadValue
    }

    companion object {
        const val MAGIC: Int = 0x45345354 // E4ST
        const val VERSION: Byte = 3
        const val OPEN: Byte = 1
        const val DATA: Byte = 2
        const val FIN: Byte = 3
        const val RESET: Byte = 4
        const val DATAGRAM: Byte = 5
        const val OPEN_ACK: Byte = 6
        const val OPEN_ACK_PAYLOAD_SIZE: Int = Byte.SIZE_BYTES + Short.SIZE_BYTES

        const val DATA_CHUNK_SIZE: Int = 32 * 1024
        const val HEADER_SIZE: Int =
            Int.SIZE_BYTES + Byte.SIZE_BYTES + Byte.SIZE_BYTES + Short.SIZE_BYTES + Int.SIZE_BYTES
        // Keep voice datagrams within a conservative single-packet payload,
        // including this protocol's header.
        const val MAX_DATAGRAM_SIZE: Int = 1_200 - HEADER_SIZE

        @JvmField
        val MAX_PACKET_SIZE: Int = HEADER_SIZE + maxOf(DATA_CHUNK_SIZE, MAX_DATAGRAM_SIZE)
        const val MAX_ACCEPTED_STEAM_PACKET_SIZE: Int = 1024 * 1024

        @JvmStatic
        fun encodeOpen(connectionId: Int, token: ByteArray): ByteArray {
            if (token.size != SteamAddress.TOKEN_LENGTH) {
                throw IllegalArgumentException("Invalid invite token length")
            }
            val buffer = header(OPEN, connectionId, SteamAddress.TOKEN_LENGTH)
            buffer.put(token)
            return buffer.array()
        }

        @JvmStatic
        fun encodeData(connectionId: Int, payload: ByteArray): ByteArray {
            if (payload.size == 0 || payload.size > DATA_CHUNK_SIZE) {
                throw IllegalArgumentException("Invalid Steam payload length: " + payload.size)
            }
            val buffer = header(DATA, connectionId, payload.size)
            buffer.put(payload)
            return buffer.array()
        }

        @JvmStatic
        fun encodeOpenAck(connectionId: Int, endpoint: VoiceChatUdpEndpoint): ByteArray {
            val buffer = header(OPEN_ACK, connectionId, OPEN_ACK_PAYLOAD_SIZE)
            buffer.put(endpoint.clientPortMode())
            buffer.putShort(endpoint.hostPort().toShort())
            return buffer.array()
        }

        @JvmStatic
        fun encodeFin(connectionId: Int): ByteArray = header(FIN, connectionId, 0).array()

        @JvmStatic
        fun encodeReset(connectionId: Int): ByteArray = header(RESET, connectionId, 0).array()

        @JvmStatic
        fun encodeDatagram(connectionId: Int, payload: ByteArray): ByteArray {
            if (payload.size == 0 || payload.size > MAX_DATAGRAM_SIZE) {
                throw IllegalArgumentException("Invalid UDP payload length: " + payload.size)
            }
            val buffer = header(DATAGRAM, connectionId, payload.size)
            buffer.put(payload)
            return buffer.array()
        }

        @JvmStatic
        fun decode(source: ByteBuffer): Frame? {
            if (source.remaining() < HEADER_SIZE) {
                return null
            }
            if (source.int != MAGIC || source.get() != VERSION) {
                return null
            }

            val type = source.get()
            source.short // Reserved for future protocol flags.
            val connectionId = source.int
            val payloadLength = source.remaining()

            if (connectionId == 0) {
                return null
            }
            if (type == OPEN && payloadLength != SteamAddress.TOKEN_LENGTH) {
                return null
            }
            if (type == OPEN_ACK && payloadLength != OPEN_ACK_PAYLOAD_SIZE) {
                return null
            }
            if (type == DATA && (payloadLength == 0 || payloadLength > DATA_CHUNK_SIZE)) {
                return null
            }
            if (type == DATAGRAM && (payloadLength == 0 || payloadLength > MAX_DATAGRAM_SIZE)) {
                return null
            }
            if ((type == FIN || type == RESET) && payloadLength != 0) {
                return null
            }
            if (type != OPEN && type != OPEN_ACK && type != DATA && type != FIN && type != RESET && type != DATAGRAM) {
                return null
            }

            val payload = ByteArray(payloadLength)
            source.get(payload)
            return Frame(type, connectionId, payload)
        }

        private fun header(type: Byte, connectionId: Int, payloadLength: Int): ByteBuffer =
            ByteBuffer.allocate(HEADER_SIZE + payloadLength)
                .putInt(MAGIC)
                .put(VERSION)
                .put(type)
                .putShort(0)
                .putInt(connectionId)
    }
}
