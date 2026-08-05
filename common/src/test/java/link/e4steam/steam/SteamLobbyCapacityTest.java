package link.e4steam.steam;

import com.codedisaster.steamworks.SteamResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SteamLobbyCapacityTest {
    @Test
    void usesMinecraftStandardIntegratedWorldCapacity() {
        assertEquals(8, SteamLobbyManager.VANILLA_LOBBY_CAPACITY);
        assertEquals(7, SteamLobbyManager.VANILLA_MAX_GUESTS);
    }

    @Test
    void vpnTimeoutsGetSeveralSequentialLobbyAttempts() {
        assertEquals(6, SteamLobbyManager.HOST_LOBBY_MAX_ATTEMPTS);
        assertTrue(SteamLobbyManager.shouldRetryHostCreation(SteamResult.Timeout, 1));
        assertTrue(SteamLobbyManager.shouldRetryHostCreation(SteamResult.Timeout, 5));
        assertTrue(SteamLobbyManager.shouldRetryHostCreation(SteamResult.NoConnection, 1));
        assertTrue(SteamLobbyManager.shouldRetryHostCreation(SteamResult.ServiceUnavailable, 1));
        assertTrue(SteamLobbyManager.shouldRetryHostCreation(SteamResult.Busy, 1));
        assertFalse(SteamLobbyManager.shouldRetryHostCreation(SteamResult.Timeout, 6));
        assertFalse(SteamLobbyManager.shouldRetryHostCreation(SteamResult.OK, 1));
    }

    @Test
    void sharingWaitsForLobbySoInviteDialogIsAvailable() {
        assertFalse(SteamLobbyManager.canStartBeforeLobby(SteamAccessMode.FRIENDS_ONLY));
        assertFalse(SteamLobbyManager.canStartBeforeLobby(SteamAccessMode.INVITE_ONLY));
        assertFalse(SteamLobbyManager.canStartBeforeLobby(SteamAccessMode.LOCAL_ONLY));
    }
}
