package link.e4steam.mixin;

import link.e4steam.E4steamClient;
import link.e4steam.MinecraftUiCompat;
import link.e4steam.Mirror;
import link.e4steam.steam.SteamSession;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Unique
    private Button e4steam$inviteButton;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void e4steam$addInviteButton(CallbackInfo ci) {
        SteamSession session = E4steamClient.session;
        if (session == null
                || session.state == SteamSession.State.STOPPED
                || session.state == SteamSession.State.STOPPING
                || session.state == SteamSession.State.UNHEALTHY) {
            return;
        }

        e4steam$inviteButton = addRenderableWidget(
                MinecraftUiCompat.button(
                        Mirror.translatable("text.e4steam_minecraft.inviteFriends"),
                        button -> e4steam$openInviteOverlay(),
                        Math.max(4, width - 154),
                        6,
                        150,
                        20
                )
        );
        MinecraftUiCompat.tooltip(
                e4steam$inviteButton,
                Mirror.translatable("text.e4steam_minecraft.inviteFriendsHelp")
        );
        e4steam$refreshInviteButton();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void e4steam$refreshInviteButtonEachTick(CallbackInfo ci) {
        e4steam$refreshInviteButton();
    }

    @Unique
    private void e4steam$openInviteOverlay() {
        Button button = e4steam$inviteButton;
        SteamSession current = E4steamClient.session;
        if (button == null || current == null || current.state != SteamSession.State.STARTED) {
            e4steam$refreshInviteButton();
            return;
        }
        button.active = false;
        button.setMessage(Mirror.translatable("text.e4steam_minecraft.steamOpening"));
        current.openInviteOverlayAsync().whenComplete((ignored, throwable) -> {
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (throwable != null) {
                    Throwable cause = throwable;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    E4steamClient.LOGGER.warn("Could not open the Steam invitation overlay", cause);
                    minecraft.gui.getChat().addMessage(
                            Mirror.translatable("text.e4steam_minecraft.overlayUnavailable")
                    );
                }
                e4steam$refreshInviteButton();
            });
        });
    }

    @Unique
    private void e4steam$refreshInviteButton() {
        Button button = e4steam$inviteButton;
        if (button == null) {
            return;
        }
        SteamSession current = E4steamClient.session;
        if (current == null
                || current.state == SteamSession.State.STOPPED
                || current.state == SteamSession.State.STOPPING
                || current.state == SteamSession.State.UNHEALTHY) {
            button.visible = false;
            button.active = false;
            return;
        }
        button.visible = true;
        boolean ready = current.state == SteamSession.State.STARTED;
        button.active = ready;
        button.setMessage(Mirror.translatable(
                ready
                        ? "text.e4steam_minecraft.inviteFriends"
                        : "text.e4steam_minecraft.steamOpening"
        ));
    }
}
