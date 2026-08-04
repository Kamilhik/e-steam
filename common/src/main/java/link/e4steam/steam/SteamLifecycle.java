package link.e4steam.steam;

import java.io.IOException;
import java.nio.file.Path;

/** Restartable ownership of the process-global Steam API. */
final class SteamLifecycle implements AutoCloseable {
    private final SteamApi api;
    private SteamNativeLibraryLoader nativeLoader;
    private boolean librariesLoaded;
    private boolean initialized;

    SteamLifecycle(SteamApi api) {
        this.api = api;
    }

    void start() throws IOException {
        if (initialized) {
            return;
        }
        if (!librariesLoaded) {
            nativeLoader = new SteamNativeLibraryLoader();
            if (!api.loadLibraries(nativeLoader)) {
                throw new IOException(
                        "Could not load Steam native libraries: " + nativeLoader.failureDescription(),
                        nativeLoader.failureCause()
                );
            }
            librariesLoaded = true;
        }
        try {
            if (!api.init()) {
                throw new IOException("SteamAPI_Init failed. Start Steam and sign in before launching Minecraft");
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("SteamAPI_Init failed: " + exception.getMessage(), exception);
        }
        initialized = true;
        if (!api.isSteamRunning()) {
            close();
            throw new IOException("Steam is not running or the current user is not signed in");
        }
    }

    void runCallbacks() {
        if (!initialized) {
            throw new IllegalStateException("Steam lifecycle is not running");
        }
        api.runCallbacks();
    }

    boolean isRunning() {
        return initialized && api.isSteamRunning();
    }

    Path steamApiPath() {
        if (!initialized || nativeLoader == null) {
            throw new IllegalStateException("Steam lifecycle is not running");
        }
        return nativeLoader.steamApiPath();
    }

    @Override
    public void close() {
        if (initialized) {
            initialized = false;
            api.shutdown();
        }
    }
}
