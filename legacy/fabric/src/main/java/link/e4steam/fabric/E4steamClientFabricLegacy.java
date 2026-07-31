package link.e4steam.fabric;

import link.e4steam.E4steamClient;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;

/**
 * Fabric 1.17/1.18 entrypoint. Those releases expose Command API v1; the
 * standard e4steam artifact uses Command API v2.
 */
public final class E4steamClientFabricLegacy implements ModInitializer {
    @Override
    public void onInitialize() {
        E4steamClient.init();
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, dedicated) -> E4steamClient.registerCommands(dispatcher)
        );
    }
}
