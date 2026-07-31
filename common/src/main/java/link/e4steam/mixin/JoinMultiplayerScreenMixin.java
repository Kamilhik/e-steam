package link.e4steam.mixin;

import link.e4steam.E4steamClient;
import link.e4steam.MinecraftUiCompat;
import link.e4steam.Mirror;
import link.e4steam.steam.SteamRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    @Unique
    private final Object e4steam$activityLock = new Object();
    @Unique
    private volatile SteamRuntime.Activity e4steam$activity;
    @Unique
    private volatile Button e4steam$friendsButton;
    @Unique
    private volatile boolean e4steam$screenActive;
    @Unique
    private volatile int e4steam$screenGeneration;

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void e4steam$addSteamFriendsButton(CallbackInfo ci) {
        synchronized (e4steam$activityLock) {
            e4steam$screenActive = true;
            e4steam$screenGeneration++;
        }

        e4steam$friendsButton = addRenderableWidget(
                MinecraftUiCompat.button(
                        Mirror.translatable("text.e4steam_minecraft.steamOpening"),
                        button -> e4steam$openFriendsOverlay(),
                        Math.max(4, width - 104),
                        6,
                        100,
                        20
                )
        );
        e4steam$friendsButton.active = false;
        MinecraftUiCompat.tooltip(
                e4steam$friendsButton,
                Mirror.translatable("text.e4steam_minecraft.steamFriendsHelp")
        );
        e4steam$startWaitingForInvites(e4steam$screenGeneration);
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void e4steam$releaseSteamActivity(CallbackInfo ci) {
        SteamRuntime.Activity activity;
        synchronized (e4steam$activityLock) {
            e4steam$screenActive = false;
            e4steam$screenGeneration++;
            activity = e4steam$activity;
            e4steam$activity = null;
            e4steam$friendsButton = null;
        }
        e4steam$closeActivity(activity);
    }

    @Unique
    private void e4steam$openFriendsOverlay() {
        Button button = e4steam$friendsButton;
        if (button == null || !button.active) {
            return;
        }

        final int generation = e4steam$screenGeneration;
        button.active = false;
        button.setMessage(Mirror.translatable("text.e4steam_minecraft.steamOpening"));

        Thread thread = new Thread(() -> {
            try {
                SteamRuntime.Activity activity = e4steam$getOrAcquireActivity(generation);
                if (activity == null) {
                    return;
                }
                SteamRuntime.get().openFriendsOverlay();
                e4steam$updateButton(
                        generation,
                        Mirror.translatable("text.e4steam_minecraft.steamFriends"),
                        true,
                        Mirror.translatable("text.e4steam_minecraft.steamFriendsHelp")
                );
            } catch (Throwable throwable) {
                E4steamClient.LOGGER.warn("Could not open the Steam friends overlay", throwable);
                e4steam$updateButton(
                        generation,
                        Mirror.translatable("text.e4steam_minecraft.steamUnavailable"),
                        true,
                        Mirror.translatable("text.e4steam_minecraft.overlayUnavailable")
                );
            }
        }, "e4steam-steam-friends-overlay");
        thread.setDaemon(true);
        thread.start();
    }

    @Unique
    private void e4steam$startWaitingForInvites(int generation) {
        Thread thread = new Thread(() -> {
            try {
                SteamRuntime.Activity activity = e4steam$getOrAcquireActivity(generation);
                if (activity == null) {
                    return;
                }
                SteamRuntime.get().awaitReady();
                e4steam$updateButton(
                        generation,
                        Mirror.translatable("text.e4steam_minecraft.steamFriends"),
                        true,
                        Mirror.translatable("text.e4steam_minecraft.steamFriendsHelp")
                );
            } catch (Throwable throwable) {
                e4steam$releaseActivityAfterFailure(generation);
                E4steamClient.LOGGER.warn("Could not start waiting for Steam invitations", throwable);
                e4steam$updateButton(
                        generation,
                        Mirror.translatable("text.e4steam_minecraft.steamUnavailable"),
                        true,
                        Mirror.translatable("text.e4steam_minecraft.steamRuntimeUnavailable")
                );
            }
        }, "e4steam-steam-invitation-wait");
        thread.setDaemon(true);
        thread.start();
    }

    @Unique
    private SteamRuntime.Activity e4steam$getOrAcquireActivity(int generation) throws Exception {
        synchronized (e4steam$activityLock) {
            if (!e4steam$screenActive || generation != e4steam$screenGeneration) {
                return null;
            }
            if (e4steam$activity != null) {
                return e4steam$activity;
            }
        }

        SteamRuntime.Activity acquired = SteamRuntime.get().acquireActivity();
        synchronized (e4steam$activityLock) {
            if (!e4steam$screenActive || generation != e4steam$screenGeneration) {
                e4steam$closeActivity(acquired);
                return null;
            }
            if (e4steam$activity == null) {
                e4steam$activity = acquired;
                return acquired;
            }
            SteamRuntime.Activity existing = e4steam$activity;
            e4steam$closeActivity(acquired);
            return existing;
        }
    }

    @Unique
    private void e4steam$releaseActivityAfterFailure(int generation) {
        SteamRuntime.Activity activity;
        synchronized (e4steam$activityLock) {
            if (generation != e4steam$screenGeneration) {
                return;
            }
            activity = e4steam$activity;
            e4steam$activity = null;
        }
        e4steam$closeActivity(activity);
    }

    @Unique
    private void e4steam$updateButton(
            int generation,
            Component message,
            boolean active,
            Component tooltip
    ) {
        Minecraft client = minecraft;
        if (client == null) {
            return;
        }
        client.execute(() -> {
            Button button = e4steam$friendsButton;
            if (e4steam$screenActive && generation == e4steam$screenGeneration && button != null) {
                button.setMessage(message);
                button.active = active;
                MinecraftUiCompat.tooltip(button, tooltip);
            }
        });
    }

    @Unique
    private static void e4steam$closeActivity(SteamRuntime.Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            activity.close();
        } catch (Throwable throwable) {
            E4steamClient.LOGGER.warn("Could not release a Steam activity", throwable);
        }
    }
}
