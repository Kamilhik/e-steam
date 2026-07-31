package link.e4steam.forge;

import link.e4steam.E4steamClient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(E4steamClient.MOD_ID)
public class E4steamClientForge {
    public E4steamClientForge() {
        if (!AgnosImpl.isClient()) {
            return;
        }
        E4steamClient.init();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommandEvent(RegisterCommandsEvent event) {
        E4steamClient.registerCommands(event.getDispatcher());
    }
}
