package link.e4steam

import folk.sisby.kaleido.api.ReflectiveConfig
import folk.sisby.kaleido.lib.quiltconfig.api.annotations.Comment
import folk.sisby.kaleido.lib.quiltconfig.api.values.TrackedValue

class Config : ReflectiveConfig() {
    companion object {
        @JvmField
        val INSTANCE: Config = ReflectiveConfig.createToml(Agnos.configDir(), "e4steam", "e4steam", Config::class.java)
    }

    @field:Comment("Whether to hide the domain on chat and only allow copying")
    @JvmField
    val hideDomainInChat: TrackedValue<Boolean> = value(false)

    @field:Comment("Allows use of dedicated server commands such as /ban and /whitelist in a shared LAN world")
    @JvmField
    val restoreDedicatedCommands: TrackedValue<Boolean> = value(true)

    @field:Comment("Whether to use the Minecraft whitelist on LAN worlds shared through Steam")
    @JvmField
    val useWhiteList: TrackedValue<Boolean> = value(false)

    @field:Comment("Whether to share opened LAN worlds through Steam P2P and Valve relays")
    @JvmField
    val hostEnabled: TrackedValue<Boolean> = value(true)

    @field:Comment("Fallback UDP port for voice mods and other UDP services; Simple Voice Chat and Plasmo Voice are detected automatically; use 0 to disable the fallback")
    @JvmField
    val voiceChatPort: TrackedValue<Int> = value(24454)
}
