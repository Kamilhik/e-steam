package link.e4steam.steam;

import com.codedisaster.steamworks.SteamAPI;

/** Production Steam API implementation backed by steamworks4j. */
final class SteamworksApi implements SteamApi {
    @Override
    public boolean loadLibraries(SteamNativeLibraryLoader loader) {
        return SteamAPI.loadLibraries(loader);
    }

    @Override
    public boolean init() throws Exception {
        return SteamAPI.init();
    }

    @Override
    public boolean isSteamRunning() {
        return SteamAPI.isSteamRunning(true);
    }

    @Override
    public void runCallbacks() {
        SteamAPI.runCallbacks();
    }

    @Override
    public void shutdown() {
        SteamAPI.shutdown();
    }
}
