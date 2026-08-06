package link.e4steam

import net.minecraft.SharedConstants
import java.lang.reflect.Modifier

/** Resolves the running game version without binding to one WorldVersion API. */
object MinecraftVersion {
    private val RELEASE_NAME = Regex(
            "^(?:1\\.\\d+(?:\\.\\d+)?|\\d{2}\\.\\d+(?:\\.\\d+)?)$"
    )

    @JvmStatic
    fun current(): String {
        for (method in SharedConstants::class.java.declaredMethods) {
            if (!Modifier.isStatic(method.modifiers)
                    || method.parameterCount != 0
                    || method.returnType.isPrimitive
                    || method.returnType != String::class.java) {
                continue
            }
            try {
                method.isAccessible = true
                val candidate = method.invoke(null)
                val name = findReleaseName(candidate)
                if (name != null) {
                    return name
                }
            } catch (_: ReflectiveOperationException) {
                // Try the next mapping/version-specific accessor.
            } catch (_: RuntimeException) {
            }
        }
        E4steamClient.LOGGER.warn("Could not determine the running Minecraft version")
        return "unknown"
    }

    private fun findReleaseName(version: Any?): String? {
        if (version == null) {
            return null
        }
        for (method in version.javaClass.methods) {
            if (method.parameterCount != 0 || method.returnType != String::class.java) {
                continue
            }
            try {
                val value = method.invoke(version)
                if (value is String && RELEASE_NAME.matches(value)) {
                    return value
                }
            } catch (_: ReflectiveOperationException) {
                // Continue through mapping-specific accessors.
            } catch (_: RuntimeException) {
            }
        }
        return null
    }
}
