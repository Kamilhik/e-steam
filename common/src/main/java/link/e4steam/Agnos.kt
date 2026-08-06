package link.e4steam

import dev.architectury.injectables.annotations.ExpectPlatform
import java.nio.file.Path

object Agnos {
    @ExpectPlatform
    @JvmStatic
    fun isClient(): Boolean = error("ExpectPlatform stub, replaced by the platform build")

    @ExpectPlatform
    @JvmStatic
    fun configDir(): Path = error("ExpectPlatform stub, replaced by the platform build")

    @ExpectPlatform
    @JvmStatic
    fun jarPath(): Path = error("ExpectPlatform stub, replaced by the platform build")
}
