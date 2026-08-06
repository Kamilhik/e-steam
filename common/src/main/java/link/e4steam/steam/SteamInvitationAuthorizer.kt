package link.e4steam.steam

import java.security.MessageDigest

/** Pure invitation/token decision logic shared by the host packet path. */
class SteamInvitationAuthorizer private constructor() {
    enum class Decision {
        ALLOWED,
        SESSION_CLOSED,
        INVALID_OR_EXPIRED_TOKEN,
        PEER_NOT_ALLOWED
    }

    companion object {
        @JvmStatic
        fun authorize(activeToken: ByteArray?, presentedToken: ByteArray?, peerAllowed: Boolean): Decision {
            if (activeToken == null) {
                return Decision.SESSION_CLOSED
            }
            if (presentedToken == null || !MessageDigest.isEqual(activeToken, presentedToken)) {
                return Decision.INVALID_OR_EXPIRED_TOKEN
            }
            return if (peerAllowed) Decision.ALLOWED else Decision.PEER_NOT_ALLOWED
        }
    }
}
