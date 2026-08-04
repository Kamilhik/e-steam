package link.e4steam.steam;

import com.codedisaster.steamworks.SteamLibraryLoader;
import link.e4steam.HexCodec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;

/**
 * Extracts steamworks4j's native libraries without relying on LWJGL's system
 * class loader. Mod loaders such as NeoForge isolate mod resources, so LWJGL
 * cannot reliably discover native files bundled at the root of the mod JAR.
 */
final class SteamNativeLibraryLoader implements SteamLibraryLoader {
    private static final String CACHE_DIRECTORY = "e4steam-steam-natives";

    private final Map<String, Path> libraries;
    private volatile Throwable failureCause;
    private volatile String failedLibrary;

    SteamNativeLibraryLoader() throws IOException {
        NativeNames names = nativeNames(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", "")
        );

        byte[] steamApi = readBundledLibrary(names.steamApi());
        byte[] steamworks4j = readBundledLibrary(names.steamworks4j());
        String fingerprint = fingerprint(names, steamApi, steamworks4j);
        Path cache = Path.of(
                System.getProperty("java.io.tmpdir"),
                CACHE_DIRECTORY,
                names.platformDirectory() + "-" + fingerprint
        ).toAbsolutePath().normalize();

        Files.createDirectories(cache);
        Path steamApiPath = materialize(cache, names.steamApi(), steamApi);
        Path steamworks4jPath = materialize(cache, names.steamworks4j(), steamworks4j);
        libraries = Map.of(
                "steam_api", steamApiPath,
                "steamworks4j", steamworks4jPath
        );
    }

    @Override
    public boolean loadLibrary(String libraryName) {
        Path library = libraries.get(libraryName);
        if (library == null) {
            failedLibrary = libraryName;
            failureCause = new IllegalArgumentException("Unexpected Steam native library: " + libraryName);
            return false;
        }

        try {
            System.load(library.toString());
            return true;
        } catch (UnsatisfiedLinkError | SecurityException throwable) {
            failedLibrary = library.getFileName().toString();
            failureCause = throwable;
            return false;
        }
    }

    Throwable failureCause() {
        return failureCause;
    }

    String failureDescription() {
        Throwable cause = failureCause;
        if (cause == null) {
            return "unknown native loading error";
        }
        String message = cause.getMessage();
        String detail = message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
        return (failedLibrary == null ? "native library" : failedLibrary) + " (" + detail + ")";
    }

    Path steamApiPath() {
        return libraries.get("steam_api");
    }

    static NativeNames nativeNames(String osName, String architecture) throws IOException {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        if (!(arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64"))) {
            throw new IOException(
                    "Unsupported Steam native architecture '" + architecture
                            + "'. This build requires a 64-bit x86 Java runtime"
            );
        }
        if (os.contains("win")) {
            return new NativeNames("windows-x64", "steam_api64.dll", "steamworks4j64.dll");
        }
        if (os.contains("linux")) {
            return new NativeNames("linux-x64", "libsteam_api.so", "libsteamworks4j.so");
        }
        throw new IOException(
                "Unsupported operating system '" + osName + "'. This build supports Windows x64 and Linux x64"
        );
    }

    private static byte[] readBundledLibrary(String resourceName) throws IOException {
        try (InputStream stream = SteamNativeLibraryLoader.class.getResourceAsStream("/" + resourceName)) {
            if (stream == null) {
                throw new IOException("Bundled Steam native library is missing: " + resourceName);
            }
            byte[] content = stream.readAllBytes();
            if (content.length == 0) {
                throw new IOException("Bundled Steam native library is empty: " + resourceName);
            }
            return content;
        }
    }

    private static Path materialize(Path directory, String fileName, byte[] expected) throws IOException {
        Path target = directory.resolve(fileName).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IOException("Invalid bundled native library name: " + fileName);
        }
        if (Files.exists(target)) {
            verifyContent(target, expected);
            return target;
        }

        Path temporary = Files.createTempFile(directory, fileName + ".", ".tmp");
        try {
            Files.write(temporary, expected);
            try {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target);
                }
            } catch (IOException exception) {
                if (!Files.exists(target)) {
                    throw exception;
                }
                // Another Minecraft process may have completed this exact
                // extraction. Only accept its file after verifying every byte.
                verifyContent(target, expected);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }

        verifyContent(target, expected);
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            target.toFile().setExecutable(true, true);
        }
        return target;
    }

    private static void verifyContent(Path path, byte[] expected) throws IOException {
        byte[] actual = Files.readAllBytes(path);
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new IOException("Refusing to load an unexpected native library from " + path);
        }
    }

    private static String fingerprint(NativeNames names, byte[] steamApi, byte[] steamworks4j)
            throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(names.platformDirectory().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(steamApi);
            digest.update(steamworks4j);
            return HexCodec.encode(digest.digest(), 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    record NativeNames(String platformDirectory, String steamApi, String steamworks4j) {
    }
}
