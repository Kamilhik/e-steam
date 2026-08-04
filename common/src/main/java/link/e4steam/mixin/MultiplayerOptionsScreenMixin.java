package link.e4steam.mixin;

import link.e4steam.Config;
import link.e4steam.E4steamClient;
import link.e4steam.MinecraftUiCompat;
import link.e4steam.Mirror;
import link.e4steam.steam.SteamAccessMode;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds e4steam access controls to the replacement LAN screen used by Minecraft 26.x. */
@Pseudo
@Mixin(targets = "net.minecraft.client.gui.screens.MultiplayerOptionsScreen", remap = false)
public abstract class MultiplayerOptionsScreenMixin extends Screen {
    protected MultiplayerOptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0, remap = false)
    private void e4steam$addSteamAccessMode(CallbackInfo ci) {
        if (!Config.INSTANCE.hostEnabled.value()) {
            return;
        }
        SteamAccessMode initialMode = E4steamClient.selectedAccessMode;
        if (initialMode == null) {
            initialMode = SteamAccessMode.FRIENDS_ONLY;
            E4steamClient.selectedAccessMode = initialMode;
        }

        CycleButton<SteamAccessMode> accessButton =
                CycleButton.<SteamAccessMode>builder(MultiplayerOptionsScreenMixin::e4steam$accessModeName)
                        .withValues(
                                SteamAccessMode.LOCAL_ONLY,
                                SteamAccessMode.FRIENDS_ONLY,
                                SteamAccessMode.INVITE_ONLY
                        )
                        .withInitialValue(initialMode)
                        .create(
                                width / 2 - 155,
                                height - 56,
                                310,
                                20,
                                Mirror.translatable("text.e4steam_minecraft.accessMode"),
                                (button, mode) -> E4steamClient.selectedAccessMode = mode
                        );
        MinecraftUiCompat.tooltip(
                accessButton,
                Mirror.translatable("text.e4steam_minecraft.accessModeHelp")
        );
        addRenderableWidget(accessButton);
    }

    @Unique
    private static Component e4steam$accessModeName(SteamAccessMode mode) {
        return Mirror.translatable(mode.translationKey());
    }
}
