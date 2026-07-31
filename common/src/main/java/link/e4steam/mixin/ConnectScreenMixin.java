package link.e4steam.mixin;

import link.e4steam.steam.SteamClientBridge;
import link.e4steam.steam.SteamRuntime;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Releases an invitation and its loopback listener when Connect is canceled. */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    // Mapped 1.20.2 name of ConnectScreen's cancel-button lambda.
    @Inject(
            method = "/^(method_19800|lambda\\$init\\$0|lambda.*cancel.*|m_95529_)$/",
            at = @At("HEAD"),
            require = 0
    )
    private void e4steam$cancelSteamConnection(Button button, CallbackInfo ci) {
        SteamClientBridge.cancelPending();
        SteamRuntime.get().cancelGuestJoin();
    }
}
