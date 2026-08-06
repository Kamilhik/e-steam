package link.e4steam;

import net.minecraft.SharedConstants;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

/** Resolves the running game version without binding to one WorldVersion API. */
public final class MinecraftVersion {
    private static final Pattern RELEASE_NAME = Pattern.compile(
            "^(?:1\\.\\d+(?:\\.\\d+)?|\\d{2}\\.\\d+(?:\\.\\d+)?(?:-(?:snapshot|pre|rc)-\\d+)?)$"
    );

    private MinecraftVersion() {
    }

    static boolean isReleaseName(String value) {
        return value != null && RELEASE_NAME.matcher(value).matches();
    }

    public static String current() {
        for (Method method : SharedConstants.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0
                    || method.getReturnType().isPrimitive()
                    || method.getReturnType() == String.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object candidate = method.invoke(null);
                String name = findReleaseName(candidate);
                if (name != null) {
                    return name;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next mapping/version-specific accessor.
            }
        }
        E4steamClient.LOGGER.warn("Could not determine the running Minecraft version");
        return "unknown";
    }

    private static String findReleaseName(Object version) {
        if (version == null) {
            return null;
        }
        for (Method method : version.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() != String.class) {
                continue;
            }
            try {
                Object value = method.invoke(version);
                if (value instanceof String text && isReleaseName(text)) {
                    return text;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Continue through mapping-specific accessors.
            }
        }
        return null;
    }
}
