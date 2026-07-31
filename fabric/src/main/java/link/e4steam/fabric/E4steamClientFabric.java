package link.e4steam.fabric;

import link.e4steam.E4steamClient;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public final class E4steamClientFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        E4steamClient.init();
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> E4steamClient.registerCommands(dispatcher)
        );
    }
}
