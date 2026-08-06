package link.e4steam.steam

import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.function.BooleanSupplier
import java.util.function.Predicate

/** Owns active TCP/UDP bridge identity and capacity independently of Steam. */
class SteamBridgeRegistry<B, U>(capacity: Int) {
    enum class Registration {
        REGISTERED,
        COLLISION,
        CAPACITY,
        UNAVAILABLE
    }

    class Key(remoteSteamId: Long, connectionId: Int) {
        private val remoteSteamIdValue = remoteSteamId
        private val connectionIdValue = connectionId

        fun remoteSteamId(): Long = remoteSteamIdValue

        fun connectionId(): Int = connectionIdValue

        override fun equals(other: Any?): Boolean =
            other is Key
                && other.remoteSteamIdValue == remoteSteamIdValue
                && other.connectionIdValue == connectionIdValue

        override fun hashCode(): Int = 31 * remoteSteamIdValue.hashCode() + connectionIdValue
    }

    private val bridges = ConcurrentHashMap<Key, B>()
    private val udpBridges = ConcurrentHashMap<Key, U>()
    private val bridgeSlots = Semaphore(capacity)

    fun nextConnectionId(remoteSteamId: Long, random: Random): Int {
        var connectionId: Int
        do {
            connectionId = random.nextInt()
        } while (connectionId == 0 || bridges.containsKey(Key(remoteSteamId, connectionId)))
        return connectionId
    }

    fun register(key: Key, bridge: B, available: BooleanSupplier): Registration {
        if (!available.getAsBoolean()) {
            return Registration.UNAVAILABLE
        }
        if (bridges.containsKey(key)) {
            return Registration.COLLISION
        }
        if (!bridgeSlots.tryAcquire()) {
            return Registration.CAPACITY
        }
        if (bridges.putIfAbsent(key, bridge) != null) {
            bridgeSlots.release()
            return Registration.COLLISION
        }
        if (!available.getAsBoolean() && bridges.remove(key, bridge)) {
            bridgeSlots.release()
            return Registration.UNAVAILABLE
        }
        return Registration.REGISTERED
    }

    fun remove(key: Key, bridge: B): Boolean {
        if (!bridges.remove(key, bridge)) {
            return false
        }
        bridgeSlots.release()
        return true
    }

    fun get(key: Key): B? = bridges[key]

    fun contains(key: Key): Boolean = bridges.containsKey(key)

    fun snapshot(): Collection<B> = ArrayList(bridges.values)

    fun any(predicate: Predicate<B>): Boolean = bridges.values.stream().anyMatch(predicate)

    fun count(predicate: Predicate<B>): Long = bridges.values.stream().filter(predicate).count()

    fun isEmpty(): Boolean = bridges.isEmpty()

    fun clear() {
        val removed = bridges.size
        bridges.clear()
        if (removed > 0) {
            bridgeSlots.release(removed)
        }
        udpBridges.clear()
    }

    fun getUdp(key: Key): U? = udpBridges[key]

    fun putUdpIfAbsent(key: Key, bridge: U): U? = udpBridges.putIfAbsent(key, bridge)

    fun removeUdp(key: Key): U? = udpBridges.remove(key)

    fun containsUdp(key: Key): Boolean = udpBridges.containsKey(key)
}
