package link.e4steam.steam

/** Testable guest invitation and lobby lifecycle, independent of native callbacks. */
class SteamGuestJoinState(deadlineMillis: Long) {
    enum class Phase {
        RESOLVING,
        WAITING_FOR_CONFIRMATION,
        CONNECTING,
        CONNECTED,
        CANCELED,
        LOST
    }

    private var phase = Phase.RESOLVING
    private var deadlineMillis: Long = deadlineMillis
    private var claimed = false

    fun waitForConfirmation() {
        if (phase == Phase.RESOLVING) {
            phase = Phase.WAITING_FOR_CONFIRMATION
            deadlineMillis = Long.MAX_VALUE
        }
    }

    fun claim(): Boolean {
        if (claimed || phase != Phase.WAITING_FOR_CONFIRMATION) {
            return false
        }
        claimed = true
        return true
    }

    fun beginConnect(deadlineMillis: Long): Boolean {
        if (phase != Phase.WAITING_FOR_CONFIRMATION) {
            return false
        }
        claimed = true
        phase = Phase.CONNECTING
        this.deadlineMillis = deadlineMillis
        return true
    }

    fun connected() {
        if (phase == Phase.CONNECTING) {
            phase = Phase.CONNECTED
            deadlineMillis = Long.MAX_VALUE
        }
    }

    fun expired(nowMillis: Long): Boolean =
        (phase == Phase.RESOLVING || phase == Phase.CONNECTING) && deadlineMillis <= nowMillis

    fun isConnected(): Boolean = phase == Phase.CONNECTED

    fun cancel() {
        if (phase != Phase.LOST) {
            phase = Phase.CANCELED
            deadlineMillis = Long.MIN_VALUE
        }
    }

    fun loseLobby() {
        phase = Phase.LOST
        deadlineMillis = Long.MIN_VALUE
    }

    fun phase(): Phase = phase
}
