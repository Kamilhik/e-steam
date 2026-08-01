package link.e4steam.steam;

import link.e4steam.Agnos;
import link.e4steam.E4steamClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Resolves the UDP endpoint advertised by common proximity voice chat mods. */
final class VoiceChatUdpEndpoint {
    static final byte CLIENT_PORT_SAME_AS_SERVER = 1;
    static final byte CLIENT_PORT_SAME_AS_MINECRAFT = 2;

    private final int hostPort;
    private final byte clientPortMode;
    private final String source;

    private VoiceChatUdpEndpoint(int hostPort, byte clientPortMode, String source) {
        this.hostPort = hostPort;
        this.clientPortMode = clientPortMode;
        this.source = source;
    }

    static VoiceChatUdpEndpoint resolve(int minecraftPort, int fallbackPort) {
        int simpleVoiceChatPort = simpleVoiceChatPort();
        if (simpleVoiceChatPort > 0) {
            return samePort(simpleVoiceChatPort, "Simple Voice Chat");
        }

        PlasmoPort plasmo = plasmoVoicePort();
        if (plasmo.detected()) {
            if (plasmo.port() > 0) {
                return samePort(plasmo.port(), "Plasmo Voice");
            }
            return new VoiceChatUdpEndpoint(
                    minecraftPort,
                    CLIENT_PORT_SAME_AS_MINECRAFT,
                    "Plasmo Voice"
            );
        }

        if (fallbackPort > 0) {
            return samePort(fallbackPort, "configured UDP service");
        }
        return new VoiceChatUdpEndpoint(0, CLIENT_PORT_SAME_AS_SERVER, "disabled");
    }

    static VoiceChatUdpEndpoint fromHandshake(int hostPort, byte clientPortMode) {
        if (hostPort < 0 || hostPort > 65535) {
            throw new IllegalArgumentException("Invalid UDP host port: " + hostPort);
        }
        if (clientPortMode != CLIENT_PORT_SAME_AS_SERVER
                && clientPortMode != CLIENT_PORT_SAME_AS_MINECRAFT) {
            throw new IllegalArgumentException("Invalid UDP client port mode: " + clientPortMode);
        }
        return new VoiceChatUdpEndpoint(hostPort, clientPortMode, "Steam host");
    }

    private static VoiceChatUdpEndpoint samePort(int port, String source) {
        return new VoiceChatUdpEndpoint(port, CLIENT_PORT_SAME_AS_SERVER, source);
    }

    int hostPort() {
        return hostPort;
    }

    int clientPort(int minecraftBridgePort) {
        return clientPortMode == CLIENT_PORT_SAME_AS_MINECRAFT ? minecraftBridgePort : hostPort;
    }

    byte clientPortMode() {
        return clientPortMode;
    }

    String source() {
        return source;
    }

    private static int simpleVoiceChatPort() {
        try {
            Class<?> voicechat = Class.forName("de.maxhenkel.voicechat.Voicechat", false, contextClassLoader());
            Field serverEventsField = voicechat.getField("SERVER");
            Object serverEvents = serverEventsField.get(null);
            if (serverEvents == null) {
                return 0;
            }
            Method getServer = serverEvents.getClass().getMethod("getServer");
            Object server = getServer.invoke(serverEvents);
            if (server == null) {
                return 0;
            }
            Object result = server.getClass().getMethod("getPort").invoke(server);
            return result instanceof Number number ? number.intValue() : 0;
        } catch (ClassNotFoundException ignored) {
            return 0;
        } catch (ReflectiveOperationException | LinkageError exception) {
            E4steamClient.LOGGER.debug("Could not read the active Simple Voice Chat UDP port", exception);
            return 0;
        }
    }

    private static PlasmoPort plasmoVoicePort() {
        Path configFolder = Agnos.configDir().resolve("plasmovoice");
        boolean detected = Files.isDirectory(configFolder)
                || classExists("su.plo.voice.api.server.PlasmoVoiceServer")
                || classExists("su.plo.voice.PlasmoVoice");
        if (!detected) {
            return new PlasmoPort(false, 0);
        }

        for (String fileName : List.of("server.toml", "config.toml")) {
            Integer configured = readHostPort(configFolder.resolve(fileName));
            if (configured != null) {
                return new PlasmoPort(true, configured);
            }
        }
        return new PlasmoPort(true, 0);
    }

    private static Integer readHostPort(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            boolean hostSection = false;
            for (String rawLine : Files.readAllLines(path)) {
                String line = rawLine.strip();
                if (line.startsWith("[") && line.endsWith("]")) {
                    hostSection = line.equals("[host]");
                    continue;
                }
                if (!hostSection || !line.startsWith("port")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String value = line.substring(equals + 1).split("#", 2)[0].strip();
                int port = Integer.parseInt(value);
                return port >= 0 && port <= 65535 ? port : null;
            }
        } catch (Exception exception) {
            E4steamClient.LOGGER.debug("Could not read Plasmo Voice UDP configuration from {}", path, exception);
        }
        return null;
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, contextClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? VoiceChatUdpEndpoint.class.getClassLoader() : loader;
    }

    private record PlasmoPort(boolean detected, int port) {
    }
}
