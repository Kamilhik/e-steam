package link.e4steam.steam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SteamLobbyCapacityTest {
    @Test
    void usesMinecraftStandardIntegratedWorldCapacity() {
        assertEquals(8, SteamLobbyManager.VANILLA_LOBBY_CAPACITY);
        assertEquals(7, SteamLobbyManager.VANILLA_MAX_GUESTS);
    }
}
