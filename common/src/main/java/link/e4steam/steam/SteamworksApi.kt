package link.e4steam.steam

import com.codedisaster.steamworks.SteamAPI

/** Production Steam API implementation backed by steamworks4j. */
class SteamworksApi : SteamApi {
    override fun loadLibraries(loader: SteamNativeLibraryLoader): Boolean = SteamAPI.loadLibraries(loader)

    @JvmName("init")
    override fun initialize(): Boolean = SteamAPI.init()

    override fun isSteamRunning(): Boolean = SteamAPI.isSteamRunning(true)

    override fun runCallbacks() = SteamAPI.runCallbacks()

    override fun shutdown() = SteamAPI.shutdown()
}
