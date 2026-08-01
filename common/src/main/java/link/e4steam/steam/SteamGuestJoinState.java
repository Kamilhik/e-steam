package link.e4steam.steam;

/** Testable guest invitation and lobby lifecycle, independent of native callbacks. */
final class SteamGuestJoinState {
    enum Phase {
        RESOLVING,
        WAITING_FOR_CONFIRMATION,
        CONNECTING,
        CONNECTED,
        CANCELED,
        LOST
    }

    private Phase phase = Phase.RESOLVING;
    private long deadlineMillis;
    private boolean claimed;

    SteamGuestJoinState(long deadlineMillis) {
        this.deadlineMillis = deadlineMillis;
    }

    void waitForConfirmation() {
        if (phase == Phase.RESOLVING) {
            phase = Phase.WAITING_FOR_CONFIRMATION;
            deadlineMillis = Long.MAX_VALUE;
        }
    }

    boolean claim() {
        if (claimed || phase != Phase.WAITING_FOR_CONFIRMATION) {
            return false;
        }
        claimed = true;
        return true;
    }

    boolean beginConnect(long deadlineMillis) {
        if (phase != Phase.WAITING_FOR_CONFIRMATION) {
            return false;
        }
        claimed = true;
        phase = Phase.CONNECTING;
        this.deadlineMillis = deadlineMillis;
        return true;
    }

    void connected() {
        if (phase == Phase.CONNECTING) {
            phase = Phase.CONNECTED;
            deadlineMillis = Long.MAX_VALUE;
        }
    }

    boolean expired(long nowMillis) {
        return (phase == Phase.RESOLVING || phase == Phase.CONNECTING)
                && deadlineMillis <= nowMillis;
    }

    boolean isConnected() {
        return phase == Phase.CONNECTED;
    }

    void cancel() {
        if (phase != Phase.LOST) {
            phase = Phase.CANCELED;
            deadlineMillis = Long.MIN_VALUE;
        }
    }

    void loseLobby() {
        phase = Phase.LOST;
        deadlineMillis = Long.MIN_VALUE;
    }

    Phase phase() {
        return phase;
    }
}
