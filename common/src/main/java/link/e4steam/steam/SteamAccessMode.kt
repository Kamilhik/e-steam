package link.e4steam.steam

/** Access policy selected for one Minecraft Open to LAN session. */
enum class SteamAccessMode(private val key: String) {
    LOCAL_ONLY("text.e4steam_minecraft.access.local"),
    FRIENDS_ONLY("text.e4steam_minecraft.access.friends"),
    INVITE_ONLY("text.e4steam_minecraft.access.invite");

    fun translationKey(): String = key
}
