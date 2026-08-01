package link.e4steam.steam;

import org.junit.jupiter.api.Test;

import static link.e4steam.steam.SteamInvitationAuthorizer.Decision.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SteamInvitationAuthorizerTest {
    private static final byte[] CURRENT = {1, 2, 3, 4};

    @Test
    void acceptsCurrentTokenFromAllowedFriend() {
        assertEquals(ALLOWED, SteamInvitationAuthorizer.authorize(CURRENT, CURRENT.clone(), true));
    }

    @Test
    void rejectsInvalidAndExpiredTokens() {
        assertEquals(INVALID_OR_EXPIRED_TOKEN,
                SteamInvitationAuthorizer.authorize(CURRENT, new byte[]{9, 9, 9, 9}, true));
        byte[] rotatedToken = {5, 6, 7, 8};
        assertEquals(INVALID_OR_EXPIRED_TOKEN,
                SteamInvitationAuthorizer.authorize(rotatedToken, CURRENT, true));
    }

    @Test
    void canceledInvitationCannotOpenClosedWorld() {
        assertEquals(SESSION_CLOSED, SteamInvitationAuthorizer.authorize(null, CURRENT, true));
    }

    @Test
    void nonFriendCannotConnectWithAValidCopiedToken() {
        assertEquals(PEER_NOT_ALLOWED, SteamInvitationAuthorizer.authorize(CURRENT, CURRENT, false));
    }
}
