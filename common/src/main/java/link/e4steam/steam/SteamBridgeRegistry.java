package link.e4steam.steam;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.Random;

/** Owns active TCP/UDP bridge identity and capacity independently of Steam. */
final class SteamBridgeRegistry<B, U> {
    enum Registration {
        REGISTERED,
        COLLISION,
        CAPACITY,
        UNAVAILABLE
    }

    record Key(long remoteSteamId, int connectionId) {
    }

    private final ConcurrentHashMap<Key, B> bridges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, U> udpBridges = new ConcurrentHashMap<>();
    private final Semaphore bridgeSlots;

    SteamBridgeRegistry(int capacity) {
        bridgeSlots = new Semaphore(capacity);
    }

    int nextConnectionId(long remoteSteamId, Random random) {
        int connectionId;
        do {
            connectionId = random.nextInt();
        } while (connectionId == 0 || bridges.containsKey(new Key(remoteSteamId, connectionId)));
        return connectionId;
    }

    Registration register(Key key, B bridge, BooleanSupplier available) {
        if (!available.getAsBoolean()) {
            return Registration.UNAVAILABLE;
        }
        if (bridges.containsKey(key)) {
            return Registration.COLLISION;
        }
        if (!bridgeSlots.tryAcquire()) {
            return Registration.CAPACITY;
        }
        if (bridges.putIfAbsent(key, bridge) != null) {
            bridgeSlots.release();
            return Registration.COLLISION;
        }
        if (!available.getAsBoolean() && bridges.remove(key, bridge)) {
            bridgeSlots.release();
            return Registration.UNAVAILABLE;
        }
        return Registration.REGISTERED;
    }

    boolean remove(Key key, B bridge) {
        if (!bridges.remove(key, bridge)) {
            return false;
        }
        bridgeSlots.release();
        return true;
    }

    B get(Key key) {
        return bridges.get(key);
    }

    boolean contains(Key key) {
        return bridges.containsKey(key);
    }

    Collection<B> snapshot() {
        return new ArrayList<>(bridges.values());
    }

    boolean any(Predicate<B> predicate) {
        return bridges.values().stream().anyMatch(predicate);
    }

    long count(Predicate<B> predicate) {
        return bridges.values().stream().filter(predicate).count();
    }

    boolean isEmpty() {
        return bridges.isEmpty();
    }

    void clear() {
        int removed = bridges.size();
        bridges.clear();
        if (removed > 0) {
            bridgeSlots.release(removed);
        }
        udpBridges.clear();
    }

    U getUdp(Key key) {
        return udpBridges.get(key);
    }

    U putUdpIfAbsent(Key key, U bridge) {
        return udpBridges.putIfAbsent(key, bridge);
    }

    U removeUdp(Key key) {
        return udpBridges.remove(key);
    }

    boolean containsUdp(Key key) {
        return udpBridges.containsKey(key);
    }
}
