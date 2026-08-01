package link.e4steam.steam;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * Bounded, category-aware outbound queue. Reliable Minecraft data, unreliable
 * voice datagrams, lobby handshakes, and terminal resets cannot consume one
 * another's reserved capacity.
 */
final class SteamOutboundQueue<B> {
    enum Kind {
        OPEN,
        OPEN_ACK,
        DATA,
        DATAGRAM,
        FIN,
        RESET
    }

    record Packet<B>(long remoteSteamId, int connectionId, byte[] payload, Kind kind, B bridge) {
    }

    private final Object lock = new Object();
    private final ArrayBlockingQueue<Packet<B>> packets;
    private final Semaphore dataSlots;
    private final Semaphore datagramSlots;
    private final Semaphore openSlots;
    private final Semaphore standaloneResetSlots;

    SteamOutboundQueue(
            int totalCapacity,
            int dataCapacity,
            int datagramCapacity,
            int openCapacity,
            int standaloneResetCapacity
    ) {
        packets = new ArrayBlockingQueue<>(totalCapacity);
        dataSlots = new Semaphore(dataCapacity);
        datagramSlots = new Semaphore(datagramCapacity);
        openSlots = new Semaphore(openCapacity);
        standaloneResetSlots = new Semaphore(standaloneResetCapacity);
    }

    boolean offerData(long remoteSteamId, int connectionId, byte[] payload, B bridge) {
        return offer(new Packet<>(remoteSteamId, connectionId, payload, Kind.DATA, bridge));
    }

    boolean offerDatagram(long remoteSteamId, int connectionId, byte[] payload, B bridge) {
        return offer(new Packet<>(remoteSteamId, connectionId, payload, Kind.DATAGRAM, bridge));
    }

    boolean offerControl(long remoteSteamId, int connectionId, byte[] payload, Kind kind, B bridge) {
        if (kind == Kind.DATA || kind == Kind.DATAGRAM) {
            throw new IllegalArgumentException("Control queue cannot accept " + kind);
        }
        return offer(new Packet<>(remoteSteamId, connectionId, payload, kind, bridge));
    }

    private boolean offer(Packet<B> packet) {
        synchronized (lock) {
            Semaphore category = categorySlots(packet);
            if (category != null && !category.tryAcquire()) {
                return false;
            }
            if (!packets.offer(packet)) {
                if (category != null) {
                    category.release();
                }
                return false;
            }
            return true;
        }
    }

    Packet<B> poll() {
        synchronized (lock) {
            Packet<B> packet = packets.poll();
            if (packet != null) {
                releaseSlot(packet);
            }
            return packet;
        }
    }

    void purge(B bridge) {
        synchronized (lock) {
            packets.removeIf(packet -> {
                if (packet.bridge() != bridge) {
                    return false;
                }
                releaseSlot(packet);
                return true;
            });
        }
    }

    void clear() {
        synchronized (lock) {
            Packet<B> packet;
            while ((packet = packets.poll()) != null) {
                releaseSlot(packet);
            }
        }
    }

    boolean isEmpty() {
        return packets.isEmpty();
    }

    private Semaphore categorySlots(Packet<B> packet) {
        return switch (packet.kind()) {
            case DATA -> dataSlots;
            case DATAGRAM -> datagramSlots;
            case OPEN, OPEN_ACK -> openSlots;
            case RESET -> packet.bridge() == null ? standaloneResetSlots : null;
            case FIN -> null;
        };
    }

    private void releaseSlot(Packet<B> packet) {
        Semaphore category = categorySlots(packet);
        if (category != null) {
            category.release();
        }
    }
}
