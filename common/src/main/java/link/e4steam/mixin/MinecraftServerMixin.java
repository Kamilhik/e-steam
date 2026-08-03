package link.e4steam.mixin;

import link.e4steam.Config;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Inject(method = "enforceSecureProfile", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4steam$enforceSecureProfile(CallbackInfoReturnable<Boolean> cir) {
        if (Config.INSTANCE.offlineMode.value()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "usesAuthentication", at = @At("HEAD"), cancellable = true, require = 0)
    private void e4steam$usesAuthentication(CallbackInfoReturnable<Boolean> cir) {
        if (Config.INSTANCE.offlineMode.value()) {
            cir.setReturnValue(false);
        }
    }
}
