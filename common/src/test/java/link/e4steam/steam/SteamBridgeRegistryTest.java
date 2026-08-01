package link.e4steam.steam;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SteamBridgeRegistryTest {
    @Test
    void rejectsCollisionsAndConcurrentCapacityOverflow() {
        SteamBridgeRegistry<String, String> registry = new SteamBridgeRegistry<>(2);
        SteamBridgeRegistry.Key first = new SteamBridgeRegistry.Key(10, 1);
        SteamBridgeRegistry.Key second = new SteamBridgeRegistry.Key(11, 2);
        SteamBridgeRegistry.Key third = new SteamBridgeRegistry.Key(12, 3);

        assertEquals(SteamBridgeRegistry.Registration.REGISTERED,
                registry.register(first, "first", () -> true));
        assertEquals(SteamBridgeRegistry.Registration.COLLISION,
                registry.register(first, "duplicate", () -> true));
        assertEquals(SteamBridgeRegistry.Registration.REGISTERED,
                registry.register(second, "second", () -> true));
        assertEquals(SteamBridgeRegistry.Registration.CAPACITY,
                registry.register(third, "third", () -> true));
    }

    @Test
    void stopAndRestartReleaseConnectionCapacity() {
        SteamBridgeRegistry<String, String> registry = new SteamBridgeRegistry<>(1);
        SteamBridgeRegistry.Key first = new SteamBridgeRegistry.Key(10, 1);
        SteamBridgeRegistry.Key restarted = new SteamBridgeRegistry.Key(10, 2);
        assertEquals(SteamBridgeRegistry.Registration.REGISTERED,
                registry.register(first, "first", () -> true));
        assertTrue(registry.remove(first, "first"));
        assertEquals(SteamBridgeRegistry.Registration.REGISTERED,
                registry.register(restarted, "restarted", () -> true));
    }

    @Test
    void refusesRegistrationAfterRuntimeBecomesUnavailable() {
        SteamBridgeRegistry<String, String> registry = new SteamBridgeRegistry<>(1);
        assertEquals(SteamBridgeRegistry.Registration.UNAVAILABLE,
                registry.register(new SteamBridgeRegistry.Key(10, 1), "bridge", () -> false));
        assertTrue(registry.isEmpty());
    }

    @Test
    void connectionIdsNeverUseZeroOrExistingPair() {
        SteamBridgeRegistry<String, String> registry = new SteamBridgeRegistry<>(2);
        SteamBridgeRegistry.Key existing = new SteamBridgeRegistry.Key(99, 7);
        registry.register(existing, "existing", () -> true);
        Random sequence = new Random() {
            private final int[] values = {0, 7, 8};
            private int index;

            @Override
            public int nextInt() {
                return values[Math.min(index++, values.length - 1)];
            }
        };
        assertEquals(8, registry.nextConnectionId(99, sequence));
    }

    @Test
    void acceptsMultiplePlayersAtTheSameTimeUpToCapacity() {
        SteamBridgeRegistry<String, String> registry = new SteamBridgeRegistry<>(31);
        for (int guest = 1; guest <= 31; guest++) {
            assertEquals(
                    SteamBridgeRegistry.Registration.REGISTERED,
                    registry.register(
                            new SteamBridgeRegistry.Key(guest, guest),
                            "guest-" + guest,
                            () -> true
                    )
            );
        }
        assertEquals(31, registry.count(value -> value.startsWith("guest-")));
        assertEquals(
                SteamBridgeRegistry.Registration.CAPACITY,
                registry.register(new SteamBridgeRegistry.Key(32, 32), "overflow", () -> true)
        );
    }
}
