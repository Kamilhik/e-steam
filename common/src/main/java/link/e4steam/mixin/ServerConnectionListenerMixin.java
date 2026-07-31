package link.e4steam.mixin;

import link.e4steam.Agnos;
import link.e4steam.Config;
import link.e4steam.E4steamClient;
import link.e4steam.steam.SteamAccessMode;
import link.e4steam.steam.SteamSession;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {
    @Inject(method = "startTcpServerListener", at = @At("TAIL"))
    private void e4steam$startSteamBridge(InetAddress inetAddress, int port, CallbackInfo ci) {
        if (!Agnos.isClient()) {
            return;
        }

        SteamSession previousSession = E4steamClient.session;
        if (previousSession != null) {
            previousSession.stop();
            if (E4steamClient.session == previousSession) {
                E4steamClient.session = null;
            }
        }

        if (!Config.INSTANCE.hostEnabled.value()) {
            return;
        }

        SteamAccessMode accessMode = E4steamClient.selectedAccessMode;
        if (accessMode == SteamAccessMode.LOCAL_ONLY) {
            return;
        }

        SteamSession session = new SteamSession(port, accessMode);
        E4steamClient.session = session;
        session.startAsync();
    }

    @Inject(method = "stop", at = @At("TAIL"))
    private void e4steam$stopSteamBridge(CallbackInfo ci) {
        SteamSession session = E4steamClient.session;
        if (session != null) {
            session.stop();
            if (E4steamClient.session == session) {
                E4steamClient.session = null;
            }
        }
    }
}
