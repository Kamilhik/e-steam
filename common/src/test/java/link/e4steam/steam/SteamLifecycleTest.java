package link.e4steam.steam;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class SteamLifecycleTest {
    @Test
    void startStopAndRestartReuseLoadedLibraries() throws Exception {
        FakeSteamApi api = new FakeSteamApi();
        SteamLifecycle lifecycle = new SteamLifecycle(api);

        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        lifecycle.close();
        assertFalse(lifecycle.isRunning());
        lifecycle.start();
        assertTrue(lifecycle.isRunning());
        lifecycle.close();

        assertEquals(1, api.loadCalls);
        assertEquals(2, api.initCalls);
        assertEquals(2, api.shutdownCalls);
    }

    @Test
    void steamDisconnectDuringGameIsObservable() throws Exception {
        FakeSteamApi api = new FakeSteamApi();
        SteamLifecycle lifecycle = new SteamLifecycle(api);
        lifecycle.start();
        api.running = false;
        assertFalse(lifecycle.isRunning());
        lifecycle.close();
        assertEquals(1, api.shutdownCalls);
    }

    @Test
    void failedSteamStartupCanBeRetried() {
        FakeSteamApi api = new FakeSteamApi();
        api.running = false;
        SteamLifecycle lifecycle = new SteamLifecycle(api);
        assertThrows(IOException.class, lifecycle::start);
        api.running = true;
        assertDoesNotThrow(lifecycle::start);
        lifecycle.close();
    }

    private static final class FakeSteamApi implements SteamApi {
        private boolean running = true;
        private int loadCalls;
        private int initCalls;
        private int shutdownCalls;

        @Override
        public boolean loadLibraries(SteamNativeLibraryLoader loader) {
            loadCalls++;
            return true;
        }

        @Override
        public boolean init() {
            initCalls++;
            return true;
        }

        @Override
        public boolean isSteamRunning() {
            return running;
        }

        @Override
        public void runCallbacks() {
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
        }
    }
}
