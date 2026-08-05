package link.e4steam.mixin;

import link.e4steam.Config;
import link.e4steam.E4steamClient;
import link.e4steam.MinecraftUiCompat;
import link.e4steam.Mirror;
import link.e4steam.steam.SteamAccessMode;
import net.minecraft.client.gui.components.Button;
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

        Button accessButton = MinecraftUiCompat.button(
                e4steam$accessModeName(initialMode),
                button -> {
                    SteamAccessMode next = e4steam$nextAccessMode(E4steamClient.selectedAccessMode);
                    E4steamClient.selectedAccessMode = next;
                    button.setMessage(e4steam$accessModeName(next));
                },
                width / 2 - 155,
                height - 56,
                310,
                20
        );
        MinecraftUiCompat.tooltip(
                accessButton,
                Mirror.translatable("text.e4steam_minecraft.accessModeHelp")
        );
        addRenderableWidget(accessButton);
    }

    @Unique
    private static Component e4steam$accessModeName(SteamAccessMode mode) {
        Component label = Mirror.append(
                Mirror.translatable("text.e4steam_minecraft.accessMode"),
                Mirror.literal(": ")
        );
        return Mirror.append(label, Mirror.translatable(mode.translationKey()));
    }

    @Unique
    private static SteamAccessMode e4steam$nextAccessMode(SteamAccessMode mode) {
        SteamAccessMode current = mode == null ? SteamAccessMode.FRIENDS_ONLY : mode;
        SteamAccessMode[] values = SteamAccessMode.values();
        return values[(current.ordinal() + 1) % values.length];
    }
}
