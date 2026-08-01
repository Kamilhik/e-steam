package link.e4steam.steam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SteamGuestJoinStateTest {
    @Test
    void canceledInvitationCannotBeClaimedOrConnected() {
        SteamGuestJoinState state = new SteamGuestJoinState(100);
        state.waitForConfirmation();
        state.cancel();
        assertFalse(state.claim());
        assertFalse(state.beginConnect(200));
        assertEquals(SteamGuestJoinState.Phase.CANCELED, state.phase());
    }

    @Test
    void connectingInvitationExpiresButConfirmationDoesNot() {
        SteamGuestJoinState state = new SteamGuestJoinState(100);
        state.waitForConfirmation();
        assertFalse(state.expired(Long.MAX_VALUE - 1));
        assertTrue(state.beginConnect(200));
        assertFalse(state.expired(199));
        assertTrue(state.expired(200));
    }

    @Test
    void lostLobbyCannotBecomeConnected() {
        SteamGuestJoinState state = new SteamGuestJoinState(100);
        state.waitForConfirmation();
        assertTrue(state.beginConnect(200));
        state.loseLobby();
        state.connected();
        assertEquals(SteamGuestJoinState.Phase.LOST, state.phase());
    }

    @Test
    void closingWorldWhileConnectingCancelsTheJoin() {
        SteamGuestJoinState state = new SteamGuestJoinState(100);
        state.waitForConfirmation();
        assertTrue(state.beginConnect(200));
        state.cancel();
        state.connected();
        assertEquals(SteamGuestJoinState.Phase.CANCELED, state.phase());
    }

    @Test
    void successfulJoinMovesToConnected() {
        SteamGuestJoinState state = new SteamGuestJoinState(100);
        state.waitForConfirmation();
        assertTrue(state.claim());
        assertTrue(state.beginConnect(200));
        state.connected();
        assertTrue(state.isConnected());
    }
}
