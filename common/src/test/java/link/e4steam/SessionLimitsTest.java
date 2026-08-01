package link.e4steam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionLimitsTest {
    @Test
    void supportsThirtyTwoPlayersIncludingTheHost() {
        assertEquals(32, SessionLimits.MAX_PLAYERS);
        assertEquals(31, SessionLimits.MAX_GUESTS);
        assertEquals(32, SessionLimits.maxPlayers());
        assertEquals(31, SessionLimits.maxGuests());
    }
}
