package link.e4steam.mixin;

import link.e4steam.E4steamClient;
import link.e4steam.Config;
import link.e4steam.MinecraftUiCompat;
import link.e4steam.Mirror;
import link.e4steam.steam.SteamAccessMode;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShareToLanScreen.class)
public abstract class ShareToLanScreenMixin extends Screen {
    protected ShareToLanScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
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
                CycleButton.<SteamAccessMode>builder(ShareToLanScreenMixin::e4steam$accessModeName)
                        .withValues(
                                SteamAccessMode.LOCAL_ONLY,
                                SteamAccessMode.FRIENDS_ONLY,
                                SteamAccessMode.INVITE_ONLY
                        )
                        .withInitialValue(initialMode)
                        .create(
                                width / 2 - 155,
                                height - 52,
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

    @Inject(method = "init", at = @At("TAIL"))
    private void e4steam$addOnlineMode(CallbackInfo ci) {
        CycleButton<Boolean> onlineModeButton = CycleButton.<Boolean>onOffBuilder()
                .withInitialValue(!Config.INSTANCE.offlineMode.value())
                .create(
                        width / 2 - 155,
                        height - 76,
                        310,
                        20,
                        Mirror.translatable("text.e4steam_minecraft.onlineMode"),
                        (button, value) -> Config.INSTANCE.offlineMode.setValue(!value, true)
                );
        MinecraftUiCompat.tooltip(
                onlineModeButton,
                Mirror.translatable("text.e4steam_minecraft.onlineModeHelp")
        );
        addRenderableWidget(onlineModeButton);
    }

    @Unique
    private static Component e4steam$accessModeName(SteamAccessMode mode) {
        return Mirror.translatable(mode.translationKey());
    }
}
