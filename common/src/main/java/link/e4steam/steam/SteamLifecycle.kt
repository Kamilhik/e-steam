package link.e4steam.steam

import java.io.IOException
import java.nio.file.Path

/** Restartable ownership of the process-global Steam API. */
class SteamLifecycle(private val api: SteamApi) : AutoCloseable {
    private var nativeLoader: SteamNativeLibraryLoader? = null
    private var librariesLoaded = false
    private var initialized = false

    @Throws(IOException::class)
    fun start() {
        if (initialized) {
            return
        }
        if (!librariesLoaded) {
            val loader = SteamNativeLibraryLoader()
            nativeLoader = loader
            if (!api.loadLibraries(loader)) {
                throw IOException(
                    "Could not load Steam native libraries: " + loader.failureDescription(),
                    loader.failureCause()
                )
            }
            librariesLoaded = true
        }
        try {
            if (!api.init()) {
                throw IOException("SteamAPI_Init failed. Start Steam and sign in before launching Minecraft")
            }
        } catch (exception: IOException) {
            throw exception
        } catch (exception: Exception) {
            throw IOException("SteamAPI_Init failed: " + exception.message, exception)
        }
        initialized = true
        if (!api.isSteamRunning()) {
            close()
            throw IOException("Steam is not running or the current user is not signed in")
        }
    }

    fun runCallbacks() {
        if (!initialized) {
            throw IllegalStateException("Steam lifecycle is not running")
        }
        api.runCallbacks()
    }

    fun isRunning(): Boolean = initialized && api.isSteamRunning()

    fun steamApiPath(): Path? {
        if (!initialized || nativeLoader == null) {
            throw IllegalStateException("Steam lifecycle is not running")
        }
        return nativeLoader!!.steamApiPath()
    }

    override fun close() {
        if (initialized) {
            initialized = false
            api.shutdown()
        }
    }
}
