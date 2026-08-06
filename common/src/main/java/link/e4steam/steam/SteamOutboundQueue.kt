package link.e4steam.steam

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore

/**
 * Bounded, category-aware outbound queue. Reliable Minecraft data, unreliable
 * voice datagrams, lobby handshakes, and terminal resets cannot consume one
 * another's reserved capacity.
 */
class SteamOutboundQueue<B>(
    totalCapacity: Int,
    dataCapacity: Int,
    datagramCapacity: Int,
    openCapacity: Int,
    standaloneResetCapacity: Int
) {
    enum class Kind {
        OPEN,
        OPEN_ACK,
        DATA,
        DATAGRAM,
        FIN,
        RESET
    }

    class Packet<B>(remoteSteamId: Long, connectionId: Int, payload: ByteArray, kind: Kind, bridge: B?) {
        private val remoteSteamIdValue = remoteSteamId
        private val connectionIdValue = connectionId
        private val payloadValue = payload
        private val kindValue = kind
        private val bridgeValue = bridge

        fun remoteSteamId(): Long = remoteSteamIdValue

        fun connectionId(): Int = connectionIdValue

        fun payload(): ByteArray = payloadValue

        fun kind(): Kind = kindValue

        fun bridge(): B? = bridgeValue
    }

    private val lock = Any()
    private val packets = ArrayBlockingQueue<Packet<B>>(totalCapacity)
    private val dataSlots = Semaphore(dataCapacity)
    private val datagramSlots = Semaphore(datagramCapacity)
    private val openSlots = Semaphore(openCapacity)
    private val standaloneResetSlots = Semaphore(standaloneResetCapacity)

    fun offerData(remoteSteamId: Long, connectionId: Int, payload: ByteArray, bridge: B?): Boolean =
        offer(Packet(remoteSteamId, connectionId, payload, Kind.DATA, bridge))

    fun offerDatagram(remoteSteamId: Long, connectionId: Int, payload: ByteArray, bridge: B?): Boolean =
        offer(Packet(remoteSteamId, connectionId, payload, Kind.DATAGRAM, bridge))

    fun offerControl(remoteSteamId: Long, connectionId: Int, payload: ByteArray, kind: Kind, bridge: B?): Boolean {
        if (kind == Kind.DATA || kind == Kind.DATAGRAM) {
            throw IllegalArgumentException("Control queue cannot accept $kind")
        }
        return offer(Packet(remoteSteamId, connectionId, payload, kind, bridge))
    }

    private fun offer(packet: Packet<B>): Boolean {
        synchronized(lock) {
            val category = categorySlots(packet)
            if (category != null && !category.tryAcquire()) {
                return false
            }
            if (!packets.offer(packet)) {
                if (category != null) {
                    category.release()
                }
                return false
            }
            return true
        }
    }

    fun poll(): Packet<B>? {
        synchronized(lock) {
            val packet = packets.poll()
            if (packet != null) {
                releaseSlot(packet)
            }
            return packet
        }
    }

    fun purge(bridge: B?) {
        synchronized(lock) {
            packets.removeIf { packet ->
                if (packet.bridge() !== bridge) {
                    return@removeIf false
                }
                releaseSlot(packet)
                true
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            var packet = packets.poll()
            while (packet != null) {
                releaseSlot(packet)
                packet = packets.poll()
            }
        }
    }

    fun isEmpty(): Boolean = packets.isEmpty()

    private fun categorySlots(packet: Packet<B>): Semaphore? =
        when (packet.kind()) {
            Kind.DATA -> dataSlots
            Kind.DATAGRAM -> datagramSlots
            Kind.OPEN, Kind.OPEN_ACK -> openSlots
            Kind.RESET -> if (packet.bridge() == null) standaloneResetSlots else null
            Kind.FIN -> null
        }

    private fun releaseSlot(packet: Packet<B>) {
        val category = categorySlots(packet)
        if (category != null) {
            category.release()
        }
    }
}
