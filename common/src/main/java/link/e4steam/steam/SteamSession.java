package link.e4steam.steam;

import link.e4steam.Config;
import link.e4steam.E4steamClient;
import link.e4steam.Mirror;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lifecycle for one Minecraft integrated-server LAN share. */
public final class SteamSession {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Object lifecycleLock = new Object();
    private final int localPort;
    private final SteamAccessMode accessMode;
    private final byte[] inviteToken = new byte[SteamAddress.TOKEN_LENGTH];
    private final AtomicBoolean startRequested = new AtomicBoolean();

    public volatile State state = State.STARTING;
    public volatile Throwable failureCause;
    private volatile SteamAddress address;
    private SteamRuntime.Activity runtimeActivity;

    public SteamSession(int localPort) {
        this(localPort, SteamAccessMode.FRIENDS_ONLY);
    }

    public SteamSession(int localPort, SteamAccessMode accessMode) {
        if (localPort < 1 || localPort > 65535) {
            throw new IllegalArgumentException("Invalid LAN port: " + localPort);
        }
        this.localPort = localPort;
        this.accessMode = java.util.Objects.requireNonNull(accessMode, "accessMode");
        SECURE_RANDOM.nextBytes(inviteToken);
    }

    public int localPort() {
        return localPort;
    }

    public SteamAddress address() {
        return address;
    }

    public SteamAccessMode accessMode() {
        return accessMode;
    }

    public void startAsync() {
        if (!startRequested.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(this::start, "e4steam-steam-session-start");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        SteamRuntime.Activity activity;
        synchronized (lifecycleLock) {
            if (state == State.STOPPED || state == State.STOPPING) {
                return;
            }
            state = State.STOPPING;
            SteamRuntime.get().stopHosting(this);
            activity = detachRuntimeActivity();
            state = State.STOPPED;
        }
        closeActivity(activity);
    }

    public CompletableFuture<Void> openInviteOverlayAsync() {
        if (state != State.STARTED) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The Steam world is not ready for invitations")
            );
        }
        try {
            return SteamRuntime.get().openHostInviteOverlay(this);
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }

    private void start() {
        SteamRuntime runtime = SteamRuntime.get();
        try {
            SteamRuntime.Activity activity = runtime.acquireActivity();
            boolean canStart;
            synchronized (lifecycleLock) {
                canStart = state == State.STARTING;
                if (canStart) {
                    runtimeActivity = activity;
                }
            }
            if (!canStart) {
                closeActivity(activity);
                return;
            }

            runtime.awaitReady();
            SteamAddress newAddress;
            CompletableFuture<Long> lobbyCreated;
            synchronized (lifecycleLock) {
                if (state != State.STARTING) {
                    return;
                }
                runtime.startHosting(
                        this,
                        localPort,
                        Config.INSTANCE.voiceChatPort.value(),
                        inviteToken,
                        accessMode
                );
                newAddress = new SteamAddress(runtime.steamIdValue(), inviteToken);
                address = newAddress;
                lobbyCreated = runtime.createHostLobby(this, accessMode, newAddress);
            }

            lobbyCreated.get(20, TimeUnit.SECONDS);
            synchronized (lifecycleLock) {
                if (state != State.STARTING) {
                    return;
                }
                state = State.STARTED;
            }

            E4steamClient.LOGGER.info(
                    "Steam LAN share ready for Steam user {}",
                    Long.toUnsignedString(newAddress.steamId())
            );
            showReadyMessage(newAddress.inviteString());
        } catch (Throwable throwable) {
            SteamRuntime.Activity activity;
            synchronized (lifecycleLock) {
                if (state != State.STARTING) {
                    return;
                }
                failureCause = throwable;
                state = State.UNHEALTHY;
                runtime.stopHosting(this);
                activity = detachRuntimeActivity();
            }
            closeActivity(activity);
            E4steamClient.LOGGER.error("Could not start the Steam LAN share", throwable);
            showFailureMessage(throwable);
        }
    }

    private void showReadyMessage(String endpoint) {
        try {
            Component message;
            if (accessMode == SteamAccessMode.INVITE_ONLY) {
                message = Mirror.translatable("text.e4steam_minecraft.privateLobbyReady");
            } else {
                boolean hidden = Config.INSTANCE.hideDomainInChat.value();
                Component visible = hidden
                        ? Mirror.translatable("text.e4steam_minecraft.hiddenDomain")
                        : Mirror.literal(endpoint);
                Component clickableAddress = Mirror.withStyle(
                        visible,
                        style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withClickEvent(Mirror.copyToClipboard(endpoint))
                                .withHoverEvent(Mirror.showText(
                                        Mirror.translatable("text.e4steam_minecraft.addressCopyHelp")
                                ))
                );
                message = Mirror.translatable("text.e4steam_minecraft.domainAssigned", clickableAddress);
            }
            Component stopButton = Mirror.withStyle(
                    Mirror.translatable("text.e4steam_minecraft.clickToStop"),
                    style -> style
                            .withColor(ChatFormatting.RED)
                            .withClickEvent(Mirror.runCommand("/e4steam stop"))
                            .withHoverEvent(Mirror.showText(
                                    Mirror.translatable("text.e4steam_minecraft.stopSharingHelp")
                            ))
            );
            Component inviteButton = Mirror.withStyle(
                    Mirror.translatable("text.e4steam_minecraft.inviteFriends"),
                    style -> style
                            .withColor(ChatFormatting.BLUE)
                            .withClickEvent(Mirror.runCommand("/e4steam invite"))
                            .withHoverEvent(Mirror.showText(
                                    Mirror.translatable("text.e4steam_minecraft.inviteFriendsHelp")
                            ))
            );
            Component readyMessage = Mirror.append(
                    Mirror.append(Mirror.append(message, Mirror.literal(" [")), inviteButton),
                    Mirror.append(Mirror.literal("] ["), Mirror.append(stopButton, Mirror.literal("]")))
            );
            Mirror.addMessageIf(
                    readyMessage,
                    () -> state == State.STARTED && E4steamClient.session == this
            );
        } catch (Throwable throwable) {
            E4steamClient.LOGGER.warn("Steam share started, but its chat message could not be displayed", throwable);
        }
    }

    private void showFailureMessage(Throwable throwable) {
        try {
            String detail = throwable.getMessage();
            Component message = Mirror.translatable("text.e4steam_minecraft.error");
            if (detail != null && !detail.isBlank()) {
                message = Mirror.append(message, Mirror.literal(": " + detail));
            }
            Component retryButton = Mirror.withStyle(
                    Mirror.translatable("text.e4steam_minecraft.steamUnavailable"),
                    style -> style.withClickEvent(Mirror.runCommand("/e4steam restart"))
            );
            Component failureMessage = Mirror.append(
                    Mirror.append(message, Mirror.literal(" [")),
                    Mirror.append(retryButton, Mirror.literal("]"))
            );
            Mirror.addMessageIf(
                    failureMessage,
                    () -> state == State.UNHEALTHY && E4steamClient.session == this
            );
        } catch (Throwable displayFailure) {
            E4steamClient.LOGGER.warn("Could not display the Steam initialization error in chat", displayFailure);
        }
    }

    void runtimeFailed(Throwable throwable) {
        SteamRuntime.Activity activity;
        synchronized (lifecycleLock) {
            if (state == State.STOPPED || state == State.STOPPING || state == State.UNHEALTHY) {
                return;
            }
            failureCause = throwable;
            state = State.UNHEALTHY;
            SteamRuntime.get().stopHosting(this);
            activity = detachRuntimeActivity();
        }
        closeActivity(activity);
        showFailureMessage(throwable);
    }

    private SteamRuntime.Activity detachRuntimeActivity() {
        SteamRuntime.Activity activity = runtimeActivity;
        runtimeActivity = null;
        return activity;
    }

    private static void closeActivity(SteamRuntime.Activity activity) {
        if (activity != null) {
            activity.close();
        }
    }

    public enum State {
        STARTING,
        STARTED,
        UNHEALTHY,
        STOPPING,
        STOPPED
    }
}
