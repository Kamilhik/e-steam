package link.e4steam.steam

import link.e4steam.Agnos
import link.e4steam.E4steamClient
import java.nio.file.Files
import java.nio.file.Path

/** Resolves the UDP endpoint advertised by common proximity voice chat mods. */
class VoiceChatUdpEndpoint private constructor(
    private val hostPort: Int,
    private val clientPortMode: Byte,
    private val source: String
) {
    fun hostPort(): Int = hostPort

    fun clientPort(minecraftBridgePort: Int): Int =
        if (clientPortMode == CLIENT_PORT_SAME_AS_MINECRAFT) minecraftBridgePort else hostPort

    fun clientPortMode(): Byte = clientPortMode

    fun source(): String = source

    companion object {
        const val CLIENT_PORT_SAME_AS_SERVER: Byte = 1
        const val CLIENT_PORT_SAME_AS_MINECRAFT: Byte = 2

        @JvmStatic
        fun resolve(minecraftPort: Int, fallbackPort: Int): VoiceChatUdpEndpoint {
            val simpleVoiceChatPort = simpleVoiceChatPort()
            if (simpleVoiceChatPort > 0) {
                return samePort(simpleVoiceChatPort, "Simple Voice Chat")
            }

            val plasmo = plasmoVoicePort()
            if (plasmo.detected) {
                if (plasmo.port > 0) {
                    return samePort(plasmo.port, "Plasmo Voice")
                }
                return VoiceChatUdpEndpoint(
                    minecraftPort,
                    CLIENT_PORT_SAME_AS_MINECRAFT,
                    "Plasmo Voice"
                )
            }

            if (fallbackPort > 0) {
                return samePort(fallbackPort, "configured UDP service")
            }
            return VoiceChatUdpEndpoint(0, CLIENT_PORT_SAME_AS_SERVER, "disabled")
        }

        @JvmStatic
        fun fromHandshake(hostPort: Int, clientPortMode: Byte): VoiceChatUdpEndpoint {
            if (hostPort < 0 || hostPort > 65535) {
                throw IllegalArgumentException("Invalid UDP host port: $hostPort")
            }
            if (clientPortMode != CLIENT_PORT_SAME_AS_SERVER
                && clientPortMode != CLIENT_PORT_SAME_AS_MINECRAFT
            ) {
                throw IllegalArgumentException("Invalid UDP client port mode: $clientPortMode")
            }
            return VoiceChatUdpEndpoint(hostPort, clientPortMode, "Steam host")
        }

        private fun samePort(port: Int, source: String): VoiceChatUdpEndpoint =
            VoiceChatUdpEndpoint(port, CLIENT_PORT_SAME_AS_SERVER, source)

        private fun simpleVoiceChatPort(): Int {
            return try {
                val voicechat = Class.forName("de.maxhenkel.voicechat.Voicechat", false, contextClassLoader())
                val serverEventsField = voicechat.getField("SERVER")
                val serverEvents = serverEventsField.get(null)
                if (serverEvents == null) {
                    return 0
                }
                val getServer = serverEvents.javaClass.getMethod("getServer")
                val server = getServer.invoke(serverEvents)
                if (server == null) {
                    return 0
                }
                val result = server.javaClass.getMethod("getPort").invoke(server)
                if (result is Number) result.toInt() else 0
            } catch (exception: ClassNotFoundException) {
                0
            } catch (exception: ReflectiveOperationException) {
                E4steamClient.LOGGER.debug("Could not read the active Simple Voice Chat UDP port", exception)
                0
            } catch (exception: LinkageError) {
                E4steamClient.LOGGER.debug("Could not read the active Simple Voice Chat UDP port", exception)
                0
            }
        }

        private fun plasmoVoicePort(): PlasmoPort {
            val configFolder = Agnos.configDir().resolve("plasmovoice")
            val detected = Files.isDirectory(configFolder)
                || classExists("su.plo.voice.api.server.PlasmoVoiceServer")
                || classExists("su.plo.voice.PlasmoVoice")
            if (!detected) {
                return PlasmoPort(false, 0)
            }

            for (fileName in listOf("server.toml", "config.toml")) {
                val configured = readHostPort(configFolder.resolve(fileName))
                if (configured != null) {
                    return PlasmoPort(true, configured)
                }
            }
            return PlasmoPort(true, 0)
        }

        private fun readHostPort(path: Path): Int? {
            if (!Files.isRegularFile(path)) {
                return null
            }
            try {
                var hostSection = false
                for (rawLine in Files.readAllLines(path)) {
                    val line = rawLine.trim()
                    if (line.startsWith("[") && line.endsWith("]")) {
                        hostSection = line == "[host]"
                        continue
                    }
                    if (!hostSection || !line.startsWith("port")) {
                        continue
                    }
                    val equals = line.indexOf('=')
                    if (equals < 0) {
                        continue
                    }
                    val value = line.substring(equals + 1).split("#", limit = 2)[0].trim()
                    val port = value.toInt()
                    return if (port in 0..65535) port else null
                }
            } catch (exception: Exception) {
                E4steamClient.LOGGER.debug("Could not read Plasmo Voice UDP configuration from {}", path, exception)
            }
            return null
        }

        private fun classExists(className: String): Boolean {
            return try {
                Class.forName(className, false, contextClassLoader())
                true
            } catch (exception: ClassNotFoundException) {
                false
            } catch (exception: LinkageError) {
                false
            }
        }

        private fun contextClassLoader(): ClassLoader {
            val loader = Thread.currentThread().contextClassLoader
            return loader ?: VoiceChatUdpEndpoint::class.java.classLoader
        }

        private data class PlasmoPort(val detected: Boolean, val port: Int)
    }
}
