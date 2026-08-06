package link.e4steam.steam

/** Replaceable boundary around the process-global Steamworks API. */
interface SteamApi {
    fun loadLibraries(loader: SteamNativeLibraryLoader): Boolean

    @Throws(Exception::class)
    fun init(): Boolean

    fun isSteamRunning(): Boolean

    fun runCallbacks()

    fun shutdown()
}
