package link.e4steam.steam

/** Replaceable boundary around the process-global Steamworks API. */
interface SteamApi {
    fun loadLibraries(loader: SteamNativeLibraryLoader): Boolean

    @JvmName("init")
    @Throws(Exception::class)
    fun initialize(): Boolean

    fun isSteamRunning(): Boolean

    fun runCallbacks()

    fun shutdown()
}
