package link.e4steam;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import link.e4steam.steam.SteamAccessMode;
import link.e4steam.steam.SteamAddress;
import link.e4steam.steam.SteamRuntime;
import link.e4steam.steam.SteamSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletionException;

public class E4steamClient {
    public static final String MOD_ID = "e4steam";
    public static volatile SteamSession session;
    public static volatile SteamAccessMode selectedAccessMode = SteamAccessMode.FRIENDS_ONLY;
    public static final Logger LOGGER = LoggerFactory.getLogger(E4steamClient.MOD_ID);

    public static void init() {
        Config.INSTANCE.id(); // Touch to initialize for McQoy
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (Config.INSTANCE.restoreDedicatedCommands.value() && Agnos.isClient()) {
            BanListCommands.register(dispatcher);
            BanPlayerCommands.register(dispatcher);
            PardonCommand.register(dispatcher);
            WhitelistCommand.register(dispatcher);
        }
        dispatcher.register(
                Commands.literal("e4steam")
                        .requires(src -> {
                            if (src.getServer() == null) {
                                return false;
                            }
                            if (src.getServer().isDedicatedServer()) {
                                return src.hasPermission(4);
                            } else {
                                try {
                                    return Mirror.isSingleplayerOwner(src.getServer(), src.getPlayerOrException());
                                } catch (CommandSyntaxException e) {
                                    return false;
                                }
                            }
                        })
                        .then(Commands.literal("stop").executes(ctx -> {
                            var current = session;
                            if (current != null
                                    && current.state != SteamSession.State.STOPPED
                                    && current.state != SteamSession.State.STOPPING) {
                                showStopConfirmation(ctx.getSource(), current);
                            } else {
                                Mirror.sendFailureToSource(ctx.getSource(), Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed"));
                            }
                            return 1;
                        }))
                        .then(Commands.literal("start").executes(ctx -> {
                            var current = session;
                            if (current == null) {
                                Mirror.sendFailureToSource(
                                        ctx.getSource(),
                                        Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed")
                                );
                                return 0;
                            }
                            if (current.state != SteamSession.State.STOPPED
                                    && current.state != SteamSession.State.UNHEALTHY) {
                                Mirror.sendFailureToSource(
                                        ctx.getSource(),
                                        Mirror.translatable("text.e4steam_minecraft.serverAlreadyStarted")
                                );
                                return 0;
                            }

                            current.stop();
                            replaceAndStartSession(current);
                            Mirror.sendSuccessToSource(
                                    ctx.getSource(),
                                    Mirror.translatable("text.e4steam_minecraft.startSharing")
                            );
                            return 1;
                        }))
                        .then(Commands.literal("doctor").executes(ctx -> {
                            var thread = new Thread(() -> {
                                LOGGER.info("generating e4steam doctor report");
                                Mirror.sendSuccessToSource(ctx.getSource(), Mirror.translatable("text.e4steam_minecraft.doctor.start"));
                                var diag = Doctor.doctor();
                                LOGGER.info("e4steam doctor report:\n{}", diag);
                                Mirror.sendSuccessToSource(ctx.getSource(), Mirror.literal(diag));
                            }, "e4steam-steam-doctor");
                            thread.setDaemon(true);
                            thread.start();
                            return 1;
                        }))
                        .then(Commands.literal("invite").executes(ctx -> {
                            var current = session;
                            if (current == null || current.state != SteamSession.State.STARTED) {
                                Mirror.sendFailureToSource(ctx.getSource(), Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed"));
                                return 0;
                            }

                            var source = ctx.getSource();
                            current.openInviteOverlayAsync().whenComplete((ignored, throwable) ->
                                    source.getServer().execute(() -> {
                                        if (throwable == null) {
                                            Mirror.sendSuccessToSource(source, Mirror.translatable("text.e4steam_minecraft.inviteFriends"));
                                        } else {
                                            Throwable cause = unwrapCompletionException(throwable);
                                            LOGGER.warn("Could not open the Steam invitation overlay", cause);
                                            Mirror.sendFailureToSource(
                                                    source,
                                                    Mirror.translatable("text.e4steam_minecraft.overlayUnavailable")
                                            );
                                        }
                                    })
                            );
                            return 1;
                        }))
                        .then(Commands.literal("restart").executes(ctx -> {
                            var current = session;
                            if (current != null) {
                                current.stop();
                                replaceAndStartSession(current);
                            } else {
                                Mirror.sendFailureToSource(ctx.getSource(), Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed"));
                            }
                            return 1;
                        }))
        );
    }

    private static void showStopConfirmation(CommandSourceStack source, SteamSession requestedSession) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            Screen previousScreen = minecraft.screen;
            minecraft.setScreen(new ConfirmScreen(confirmed -> {
                minecraft.setScreen(previousScreen);
                if (!confirmed) {
                    return;
                }

                source.getServer().execute(() -> {
                    if (session != requestedSession
                            || requestedSession.state == SteamSession.State.STOPPED
                            || requestedSession.state == SteamSession.State.STOPPING) {
                        Mirror.sendFailureToSource(
                                source,
                                Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed")
                        );
                        return;
                    }
                    requestedSession.stop();
                    Mirror.sendSuccessToSource(
                            source,
                            Mirror.translatable("text.e4steam_minecraft.closeServer")
                    );
                });
            },
                    Mirror.translatable("text.e4steam_minecraft.stopConfirmTitle"),
                    Mirror.translatable("text.e4steam_minecraft.stopConfirmMessage"),
                    Mirror.translatable("text.e4steam_minecraft.stopConfirmYes"),
                    Mirror.translatable("text.e4steam_minecraft.stopConfirmNo")));
        });
    }

    private static void replaceAndStartSession(SteamSession previous) {
        SteamSession replacement = new SteamSession(previous.localPort(), previous.accessMode());
        session = replacement;
        replacement.startAsync();
    }

    /** Called by the Steam callback thread after a validated lobby invitation was accepted. */
    public static void acceptSteamInvite(String endpoint, String hostName) {
        if (SteamAddress.tryParse(endpoint).isEmpty()) {
            showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinInvalidAddress"));
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            String displayName = normalizedHostName(hostName);
            if (minecraft.screen instanceof ConnectScreen) {
                SteamRuntime.get().cancelGuestJoin();
                minecraft.gui.getChat().addMessage(
                        Mirror.translatable("text.e4steam_minecraft.joinAlreadyConnecting")
                );
                return;
            }
            if (minecraft.level == null) {
                Screen parent = currentOrMultiplayerScreen(minecraft);
                claimSteamInviteAndConnect(minecraft, endpoint, displayName, parent, null, false);
                return;
            }

            Screen previousScreen = minecraft.screen;
            Component title = Mirror.translatable("text.e4steam_minecraft.joinInviteTitle");
            Component message = Mirror.translatable("text.e4steam_minecraft.joinInviteMessage", displayName);
            minecraft.setScreen(new ConfirmScreen(confirmed -> {
                if (!confirmed) {
                    SteamRuntime.get().cancelGuestJoin();
                    minecraft.setScreen(previousScreen);
                    return;
                }

                Screen returnScreen = multiplayerScreen();
                minecraft.setScreen(MinecraftUiCompat.messageScreen(
                        Mirror.translatable("connect.connecting"),
                        previousScreen
                ));
                claimSteamInviteAndConnect(
                        minecraft,
                        endpoint,
                        displayName,
                        returnScreen,
                        previousScreen,
                        true
                );
            }, title, message,
                    Mirror.translatable("text.e4steam_minecraft.joinInviteConfirm"),
                    Mirror.translatable("text.e4steam_minecraft.joinInviteStay")));
        });
    }

    /** Displays an invitation/join error without touching Minecraft UI from a Steam callback thread. */
    public static void showSteamJoinFailure(String detail) {
        Component reason = Mirror.translatable("text.e4steam_minecraft.connectionError");
        if (detail != null && !detail.isBlank()) {
            reason = Mirror.append(reason, Mirror.literal(": " + detail));
        }
        showSteamJoinFailure(reason);
    }

    /** Displays a localized invitation/join error on the Minecraft thread. */
    public static void showSteamJoinFailure(Component reason) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.level != null || minecraft.screen instanceof ConnectScreen) {
                minecraft.gui.getChat().addMessage(reason);
                return;
            }

            Screen parent = currentOrMultiplayerScreen(minecraft);
            minecraft.setScreen(new DisconnectedScreen(
                    parent,
                    Mirror.translatable("connect.failed"),
                    reason
            ));
        });
    }

    private static void connectToSteamHost(
            Minecraft minecraft,
            String endpoint,
            String hostName,
            Screen parent
    ) {
        try {
            MinecraftUiCompat.connect(
                    parent,
                    minecraft,
                    ServerAddress.parseString(endpoint),
                    hostName,
                    endpoint
            );
        } catch (Throwable throwable) {
            LOGGER.error("Could not begin connecting to a Steam invitation", throwable);
            SteamRuntime.get().cancelGuestJoin();
            showSteamJoinFailure(throwable.getMessage());
        }
    }

    private static void claimSteamInviteAndConnect(
            Minecraft minecraft,
            String endpoint,
            String hostName,
            Screen parent,
            Screen rejectionScreen,
            boolean disconnectCurrent
    ) {
        var claim = disconnectCurrent
                ? SteamRuntime.get().claimGuestInvite(endpoint)
                : SteamRuntime.get().beginGuestConnect(endpoint);
        claim.whenComplete((claimed, throwable) ->
                minecraft.execute(() -> {
                    if (throwable != null || !Boolean.TRUE.equals(claimed)) {
                        rejectSteamInvite(minecraft, rejectionScreen, throwable);
                        return;
                    }

                    if (disconnectCurrent && minecraft.level != null) {
                        try {
                            MinecraftUiCompat.disconnect(minecraft, parent);
                        } catch (ReflectiveOperationException disconnectFailure) {
                            rejectSteamInvite(minecraft, rejectionScreen, disconnectFailure);
                            return;
                        }
                    }
                    if (!disconnectCurrent) {
                        connectToSteamHost(minecraft, endpoint, hostName, parent);
                        return;
                    }

                    // Integrated-server shutdown can block while the world is
                    // saved. Start the 30-second connection window only after
                    // that completes, and revalidate that the lobby survived.
                    SteamRuntime.get().beginGuestConnect(endpoint).whenComplete((armed, armFailure) ->
                            minecraft.execute(() -> {
                                if (armFailure != null || !Boolean.TRUE.equals(armed)) {
                                    rejectSteamInvite(minecraft, null, armFailure);
                                    return;
                                }
                                connectToSteamHost(minecraft, endpoint, hostName, parent);
                            })
                    );
                })
        );
    }

    private static void rejectSteamInvite(Minecraft minecraft, Screen rejectionScreen, Throwable throwable) {
        if (throwable != null) {
            LOGGER.warn("Could not claim the Steam invitation", unwrapCompletionException(throwable));
        }
        if (minecraft.level != null) {
            minecraft.setScreen(rejectionScreen);
        }
        showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinExpired"));
    }

    private static Screen currentOrMultiplayerScreen(Minecraft minecraft) {
        return minecraft.screen != null ? minecraft.screen : multiplayerScreen();
    }

    private static Screen multiplayerScreen() {
        return new JoinMultiplayerScreen(new TitleScreen());
    }

    private static String normalizedHostName(String hostName) {
        if (hostName == null || hostName.isBlank()) {
            return Mirror.translatable("text.e4steam_minecraft.steamFriend").getString();
        }
        String normalized = hostName.replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        if (normalized.isEmpty()) {
            return Mirror.translatable("text.e4steam_minecraft.steamFriend").getString();
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
