package link.e4steam;

/** Shared player limits for the Minecraft LAN world and its Steam lobby. */
public final class SessionLimits {
    public static final int MAX_PLAYERS = 32;
    public static final int MAX_GUESTS = MAX_PLAYERS - 1;

    private SessionLimits() {
    }

    public static int maxPlayers() {
        return MAX_PLAYERS;
    }

    public static int maxGuests() {
        return MAX_GUESTS;
    }
}
