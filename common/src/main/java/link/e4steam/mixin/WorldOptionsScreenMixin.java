package link.e4steam.mixin;

import link.e4steam.Config;
import link.e4steam.E4steamClient;
import link.e4steam.MinecraftUiCompat;
import link.e4steam.Mirror;
import link.e4steam.steam.SteamAccessMode;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/** Adds the Steam access control to the scrollable world-options layout introduced in 26.3. */
@Pseudo
@Mixin(targets = "net.minecraft.client.gui.screens.WorldOptionsScreen", remap = false)
public abstract class WorldOptionsScreenMixin extends Screen {
    protected WorldOptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "multiplayerOptions", at = @At("TAIL"), require = 0, remap = false)
    private void e4steam$addSteamAccessMode(
            LinearLayout options,
            IntegratedServer server,
            CallbackInfo ci
    ) {
        if (!Config.INSTANCE.hostEnabled.value()) {
            return;
        }

        List<GridLayout> grids = new ArrayList<>(2);
        options.visitChildren(child -> {
            if (child instanceof GridLayout grid) {
                grids.add(grid);
            }
        });
        if (grids.isEmpty()) {
            return;
        }

        // Vanilla rows are: title, LAN/permissions, then port. Put Steam on
        // its own full-width row so the scroll area owns rendering and input.
        GridLayout multiplayerOptions = grids.get(grids.size() - 1);
        multiplayerOptions.addChild(e4steam$createAccessButton(), 3, 0, 1, 2);
    }

    @Unique
    private Button e4steam$createAccessButton() {
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
                0,
                0,
                310,
                20
        );
        MinecraftUiCompat.tooltip(
                accessButton,
                Mirror.translatable("text.e4steam_minecraft.accessModeHelp")
        );
        return accessButton;
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
