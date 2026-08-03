package link.e4steam;

import folk.sisby.kaleido.api.ReflectiveConfig;
import folk.sisby.kaleido.lib.quiltconfig.api.annotations.Comment;
import folk.sisby.kaleido.lib.quiltconfig.api.values.TrackedValue;

public class Config extends ReflectiveConfig {
    public static final Config INSTANCE = Config.createToml(Agnos.configDir(), "e4steam", "e4steam", Config.class);

    @Comment("Whether to hide the domain on chat and only allow copying")
    public final TrackedValue<Boolean> hideDomainInChat = this.value(false);

    @Comment("Allows use of dedicated server commands such as /ban and /whitelist in a shared LAN world")
    public final TrackedValue<Boolean> restoreDedicatedCommands = this.value(true);

    @Comment("Whether to use the Minecraft whitelist on LAN worlds shared through Steam")
    public final TrackedValue<Boolean> useWhiteList = this.value(false);

    @Comment("Whether to allow players without a Minecraft license (offline mode). They get name-based accounts and no skins")
    public final TrackedValue<Boolean> offlineMode = this.value(false);

    @Comment("Whether to share opened LAN worlds through Steam P2P and Valve relays")
    public final TrackedValue<Boolean> hostEnabled = this.value(true);

    @Comment("Fallback UDP port for voice mods and other UDP services; Simple Voice Chat and Plasmo Voice are detected automatically; use 0 to disable the fallback")
    public final TrackedValue<Integer> voiceChatPort = this.value(24454);
}
