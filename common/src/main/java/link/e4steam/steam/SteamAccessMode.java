package link.e4steam.steam;

/** Access policy selected for one Minecraft Open to LAN session. */
public enum SteamAccessMode {
    LOCAL_ONLY("text.e4steam_minecraft.access.local"),
    FRIENDS_ONLY("text.e4steam_minecraft.access.friends"),
    INVITE_ONLY("text.e4steam_minecraft.access.invite");

    private final String translationKey;

    SteamAccessMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }
}
