package link.e4steam.steam

import com.codedisaster.steamworks.SteamLibraryLoader
import link.e4steam.HexCodec
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale

/**
 * Extracts steamworks4j's native libraries without relying on LWJGL's system
 * class loader. Mod loaders such as NeoForge isolate mod resources, so LWJGL
 * cannot reliably discover native files bundled at the root of the mod JAR.
 */
class SteamNativeLibraryLoader @Throws(IOException::class) constructor() : SteamLibraryLoader {
    private val libraries: Map<String, Path>
    @Volatile
    private var failureCause: Throwable? = null
    @Volatile
    private var failedLibrary: String? = null

    init {
        val names = nativeNames(
            System.getProperty("os.name", ""),
            System.getProperty("os.arch", "")
        )

        val steamApi = readBundledLibrary(names.steamApi())
        val steamworks4j = readBundledLibrary(names.steamworks4j())
        val fingerprint = fingerprint(names, steamApi, steamworks4j)
        val cache = Path.of(
            System.getProperty("java.io.tmpdir"),
            CACHE_DIRECTORY,
            names.platformDirectory() + "-" + fingerprint
        ).toAbsolutePath().normalize()

        Files.createDirectories(cache)
        val steamApiPath = materialize(cache, names.steamApi(), steamApi)
        val steamworks4jPath = materialize(cache, names.steamworks4j(), steamworks4j)
        libraries = mapOf(
            "steam_api" to steamApiPath,
            "steamworks4j" to steamworks4jPath
        )
    }

    override fun loadLibrary(libraryName: String): Boolean {
        val library = libraries[libraryName]
        if (library == null) {
            failedLibrary = libraryName
            failureCause = IllegalArgumentException("Unexpected Steam native library: $libraryName")
            return false
        }

        return try {
            System.load(library.toString())
            true
        } catch (throwable: UnsatisfiedLinkError) {
            failedLibrary = library.fileName.toString()
            failureCause = throwable
            false
        } catch (throwable: SecurityException) {
            failedLibrary = library.fileName.toString()
            failureCause = throwable
            false
        }
    }

    fun failureCause(): Throwable? = failureCause

    fun failureDescription(): String {
        val cause = failureCause
        if (cause == null) {
            return "unknown native loading error"
        }
        val message = cause.message
        val detail = if (message == null || message.isBlank()) {
            cause.javaClass.simpleName
        } else {
            cause.javaClass.simpleName + ": " + message
        }
        return (if (failedLibrary == null) "native library" else failedLibrary) + " ($detail)"
    }

    fun steamApiPath(): Path? = libraries["steam_api"]

    class NativeNames(platformDirectory: String, steamApi: String, steamworks4j: String) {
        private val platformDirectoryValue = platformDirectory
        private val steamApiValue = steamApi
        private val steamworks4jValue = steamworks4j

        fun platformDirectory(): String = platformDirectoryValue

        fun steamApi(): String = steamApiValue

        fun steamworks4j(): String = steamworks4jValue
    }

    companion object {
        private const val CACHE_DIRECTORY = "e4steam-steam-natives"

        @JvmStatic
        @Throws(IOException::class)
        fun nativeNames(osName: String, architecture: String): NativeNames {
            val os = osName.lowercase(Locale.ROOT)
            val arch = architecture.lowercase(Locale.ROOT)
            if (!(arch == "amd64" || arch == "x86_64" || arch == "x64")) {
                throw IOException(
                    "Unsupported Steam native architecture '" + architecture
                        + "'. This build requires a 64-bit x86 Java runtime"
                )
            }
            if (os.contains("win")) {
                return NativeNames("windows-x64", "steam_api64.dll", "steamworks4j64.dll")
            }
            if (os.contains("linux")) {
                return NativeNames("linux-x64", "libsteam_api.so", "libsteamworks4j.so")
            }
            throw IOException(
                "Unsupported operating system '" + osName + "'. This build supports Windows x64 and Linux x64"
            )
        }

        @Throws(IOException::class)
        private fun readBundledLibrary(resourceName: String): ByteArray {
            (SteamNativeLibraryLoader::class.java.getResourceAsStream("/$resourceName")).use { stream ->
                if (stream == null) {
                    throw IOException("Bundled Steam native library is missing: $resourceName")
                }
                val content = stream.readAllBytes()
                if (content.isEmpty()) {
                    throw IOException("Bundled Steam native library is empty: $resourceName")
                }
                return content
            }
        }

        @Throws(IOException::class)
        private fun materialize(directory: Path, fileName: String, expected: ByteArray): Path {
            val target = directory.resolve(fileName).normalize()
            if (target.parent != directory) {
                throw IOException("Invalid bundled native library name: $fileName")
            }
            if (Files.exists(target)) {
                verifyContent(target, expected)
                return target
            }

            val temporary = Files.createTempFile(directory, "$fileName.", ".tmp")
            try {
                Files.write(temporary, expected)
                try {
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                    } catch (exception: AtomicMoveNotSupportedException) {
                        Files.move(temporary, target)
                    }
                } catch (exception: IOException) {
                    if (!Files.exists(target)) {
                        throw exception
                    }
                    // Another Minecraft process may have completed this exact
                    // extraction. Only accept its file after verifying every byte.
                    verifyContent(target, expected)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }

            verifyContent(target, expected)
            if (!System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")) {
                target.toFile().setExecutable(true, true)
            }
            return target
        }

        @Throws(IOException::class)
        private fun verifyContent(path: Path, expected: ByteArray) {
            val actual = Files.readAllBytes(path)
            if (!MessageDigest.isEqual(actual, expected)) {
                throw IOException("Refusing to load an unexpected native library from $path")
            }
        }

        @Throws(IOException::class)
        private fun fingerprint(names: NativeNames, steamApi: ByteArray, steamworks4j: ByteArray): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(names.platformDirectory().toByteArray(StandardCharsets.US_ASCII))
                digest.update(steamApi)
                digest.update(steamworks4j)
                HexCodec.encode(digest.digest(), 0, 12)
            } catch (exception: NoSuchAlgorithmException) {
                throw IOException("SHA-256 is unavailable", exception)
            }
        }
    }
}
