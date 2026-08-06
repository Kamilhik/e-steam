package link.e4steam

import link.e4steam.steam.SteamRuntime
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

object Doctor {
    @JvmStatic
    fun doctor(): String {
        val result = StringBuilder()
        result.append("mod sha512sum: ")
        try {
            val bytes = Files.readAllBytes(Agnos.jarPath())
            val md = MessageDigest.getInstance("SHA-512")
            val digest = md.digest(bytes)
            result.append(HexCodec.encode(digest))
        } catch (e: Exception) {
            result.append("exception during digest:\n")
            val baos = ByteArrayOutputStream()
            e.printStackTrace(PrintStream(baos, true, StandardCharsets.UTF_8))
            result.append(baos.toString(StandardCharsets.UTF_8))
        }
        result.append("\n")

        val runtime = SteamRuntime.get()
        result.append("Steam runtime status: ")
        try {
            result.append(runtime.statusSummary()).append("\n")
        } catch (e: Exception) {
            result.append("exception while reading status:\n")
            appendThrowable(result, e)
        }

        result.append("Steam ID: ")
        try {
            result.append(runtime.steamId().toString()).append("\n")
        } catch (e: Exception) {
            result.append("exception while reading Steam ID:\n")
            appendThrowable(result, e)
        }

        result.append("Steam runtime recorded exception:\n")
        var runtimeFailure: Throwable? = null
        try {
            runtimeFailure = runtime.failureCause()
        } catch (e: Exception) {
            runtimeFailure = e
        }
        if (runtimeFailure != null) {
            appendThrowable(result, runtimeFailure)
        } else {
            result.append("none recorded.\n")
        }

        result.append("Steam session:\n")
        val session = E4steamClient.session
        if (session == null) {
            result.append("none.\n")
        } else {
            result.append("state: ").append(session.state).append("\n")
            result.append("local port: ").append(session.localPort()).append("\n")
            result.append("recorded exception:\n")
            if (session.failureCause != null) {
                appendThrowable(result, session.failureCause)
            } else {
                result.append("none recorded.\n")
            }
        }
        return result.toString()
    }

    private fun appendThrowable(result: StringBuilder, throwable: Throwable) {
        val baos = ByteArrayOutputStream()
        throwable.printStackTrace(PrintStream(baos, true, StandardCharsets.UTF_8))
        result.append(baos.toString(StandardCharsets.UTF_8))
        result.append("\n")
    }
}
