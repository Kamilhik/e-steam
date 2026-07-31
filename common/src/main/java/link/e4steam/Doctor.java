package link.e4steam;

import link.e4steam.steam.SteamRuntime;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

public class Doctor {
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
}
