package link.e4steam.steam;

import com.codedisaster.steamworks.SteamAPI;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SteamNativeLibraryLoaderTest {
    @Test
    void selectsWindowsX64Libraries() throws Exception {
        SteamNativeLibraryLoader.NativeNames names =
                SteamNativeLibraryLoader.nativeNames("Windows 11", "amd64");

        assertEquals("windows-x64", names.platformDirectory());
        assertEquals("steam_api64.dll", names.steamApi());
        assertEquals("steamworks4j64.dll", names.steamworks4j());
    }

    @Test
    void selectsLinuxX64Libraries() throws Exception {
        SteamNativeLibraryLoader.NativeNames names =
                SteamNativeLibraryLoader.nativeNames("Linux", "x86_64");

        assertEquals("linux-x64", names.platformDirectory());
        assertEquals("libsteam_api.so", names.steamApi());
        assertEquals("libsteamworks4j.so", names.steamworks4j());
    }

    @Test
    void rejectsUnsupportedArchitecture() {
        assertThrows(
                IOException.class,
                () -> SteamNativeLibraryLoader.nativeNames("Windows 11", "aarch64")
        );
    }

    @Test
    void extractsAndLoadsBundledLibraries() throws Exception {
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        boolean supportedOs = os.toLowerCase(java.util.Locale.ROOT).contains("win")
                || os.toLowerCase(java.util.Locale.ROOT).contains("linux");
        boolean supportedArch = arch.equalsIgnoreCase("amd64")
                || arch.equalsIgnoreCase("x86_64")
                || arch.equalsIgnoreCase("x64");
        assumeTrue(supportedOs && supportedArch);

        SteamNativeLibraryLoader loader = new SteamNativeLibraryLoader();
        assertTrue(SteamAPI.loadLibraries(loader), loader.failureDescription());
    }
}
