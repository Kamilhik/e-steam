package link.e4steam;

import link.e4steam.steam.SteamRuntime;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

public class Doctor {
    /**
     * Short report intended for Minecraft chat. Stack traces remain in the
     * detailed report written to latest.log and must not flood the chat UI.
     */
    public static String chatSummary() {
        var result = new StringBuilder("e4steam diagnostics\n");
        var runtime = SteamRuntime.get();

        Throwable runtimeFailure = null;
        try {
            result.append("Steam runtime: ").append(runtime.statusSummary()).append("\n");
            runtimeFailure = runtime.failureCause();
        } catch (Exception exception) {
            result.append("Steam runtime: unavailable\n");
            runtimeFailure = exception;
        }

        var session = E4steamClient.session;
        Throwable sessionFailure = null;
        if (session == null) {
            result.append("Steam session: none\n");
        } else {
            result.append("Steam session: ").append(session.state).append("\n");
            sessionFailure = session.failureCause;
        }

        Throwable failure = sessionFailure != null ? sessionFailure : runtimeFailure;
        if (failure == null) {
            result.append("No errors detected.");
        } else {
            result.append("Problem: ").append(shortMessage(failure)).append("\n");
            result.append("Full technical report: latest.log");
        }
        return result.toString();
    }

    public static String doctor() {
        var result = new StringBuilder();
        result.append("mod sha512sum: ");
        try {
            var bytes = Files.readAllBytes(Agnos.jarPath());
            var md = MessageDigest.getInstance("SHA-512");
            var digest = md.digest(bytes);
            result.append(HexCodec.encode(digest));
        } catch (Exception e) {
            result.append("exception during digest:\n");
            var baos = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(baos, true, StandardCharsets.UTF_8));
            result.append(baos.toString(StandardCharsets.UTF_8));
        }
        result.append("\n");

        var runtime = SteamRuntime.get();
        result.append("Steam runtime status: ");
        try {
            result.append(runtime.statusSummary()).append("\n");
        } catch (Exception e) {
            result.append("exception while reading status:\n");
            appendThrowable(result, e);
        }

        result.append("Steam ID: ");
        try {
            result.append(String.valueOf(runtime.steamId())).append("\n");
        } catch (Exception e) {
            result.append("exception while reading Steam ID:\n");
            appendThrowable(result, e);
        }

        result.append("Steam runtime recorded exception:\n");
        Throwable runtimeFailure = null;
        try {
            runtimeFailure = runtime.failureCause();
        } catch (Exception e) {
            runtimeFailure = e;
        }
        if (runtimeFailure != null) {
            appendThrowable(result, runtimeFailure);
        } else {
            result.append("none recorded.\n");
        }

        result.append("Steam session:\n");
        var session = E4steamClient.session;
        if (session == null) {
            result.append("none.\n");
        } else {
            result.append("state: ").append(session.state).append("\n");
            result.append("local port: ").append(session.localPort()).append("\n");
            result.append("recorded exception:\n");
            if (session.failureCause != null) {
                appendThrowable(result, session.failureCause);
            } else {
                result.append("none recorded.\n");
            }
        }
        return result.toString();
    }

    private static void appendThrowable(StringBuilder result, Throwable throwable) {
        var baos = new ByteArrayOutputStream();
        throwable.printStackTrace(new PrintStream(baos, true, StandardCharsets.UTF_8));
        result.append(baos.toString(StandardCharsets.UTF_8));
        result.append("\n");
    }

    static String shortMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        if (message == null) {
            message = throwable.getClass().getSimpleName();
        }
        message = message.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return message.length() <= 240 ? message : message.substring(0, 237) + "...";
    }
}
