package link.e4steam.neoforge;

import link.e4steam.E4steamClient;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(E4steamClient.MOD_ID)
public class E4steamClientNeoForge {
    public E4steamClientNeoForge() {
        if (!AgnosImpl.isClient()) {
            return;
        }
        E4steamClient.init();
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommandEvent(RegisterCommandsEvent event) {
        E4steamClient.registerCommands(event.getDispatcher());
    }
}
