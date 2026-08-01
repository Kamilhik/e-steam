package link.e4steam.steam;

/** Replaceable boundary around the process-global Steamworks API. */
interface SteamApi {
    boolean loadLibraries(SteamNativeLibraryLoader loader);

    boolean init() throws Exception;

    boolean isSteamRunning();

    void runCallbacks();

    void shutdown();
}
