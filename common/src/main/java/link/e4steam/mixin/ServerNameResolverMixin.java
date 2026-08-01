package link.e4steam.mixin;

import link.e4steam.E4steamClient;
import link.e4steam.steam.SteamAddress;
import link.e4steam.steam.SteamClientBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;

@Mixin(ServerNameResolver.class)
public class ServerNameResolverMixin {
    @Redirect(
            method = "resolveAddress",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddressResolver;resolve(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"
            )
    )
    private Optional<ResolvedServerAddress> e4steam$resolveSteamAddress(
            ServerAddressResolver instance,
            ServerAddress serverAddress
    ) {
        Optional<SteamAddress> steamAddress = SteamAddress.tryParse(serverAddress.getHost());
        if (steamAddress.isEmpty()) {
            // Server-list pings also use this resolver. Only stop Spacewar
            // while Minecraft is actually on its connection screen.
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen instanceof ConnectScreen) {
                E4steamClient.stopSteamForDirectServerConnection();
            }
            return instance.resolve(serverAddress);
        }

        try {
            InetSocketAddress localAddress = SteamClientBridge.open(steamAddress.get());
            return Optional.of(ResolvedServerAddress.from(localAddress));
        } catch (IOException exception) {
            E4steamClient.LOGGER.error(
                    "Failed to create the local Steam bridge for Steam user {}",
                    Long.toUnsignedString(steamAddress.get().steamId()),
                    exception
            );
            return Optional.empty();
        }
    }
}
