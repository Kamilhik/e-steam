package link.e4steam.steam

import link.e4steam.Config
import link.e4steam.E4steamClient
import link.e4steam.Mirror
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Lifecycle for one Minecraft integrated-server LAN share. */
class SteamSession(localPort: Int, accessMode: SteamAccessMode) {
    private val localPortValue = localPort
    private val accessModeValue = accessMode
    private val lifecycleLock = Any()
    private val inviteToken = ByteArray(SteamAddress.TOKEN_LENGTH).also { SECURE_RANDOM.nextBytes(it) }
    private val startRequested = AtomicBoolean()

    @JvmField
    @Volatile
    var state: State = State.STARTING

    @JvmField
    @Volatile
    var failureCause: Throwable? = null

    @Volatile
    private var addressValue: SteamAddress? = null

    private var runtimeActivity: SteamRuntime.Activity? = null

    init {
        if (localPort < 1 || localPort > 65535) {
            throw IllegalArgumentException("Invalid LAN port: $localPort")
        }
    }

    constructor(localPort: Int) : this(localPort, SteamAccessMode.FRIENDS_ONLY)

    fun localPort(): Int = localPortValue

    fun address(): SteamAddress? = addressValue

    fun accessMode(): SteamAccessMode = accessModeValue

    fun startAsync() {
        if (!startRequested.compareAndSet(false, true)) {
            return
        }

        val thread = Thread({ start() }, "e4steam-steam-session-start")
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        var activity: SteamRuntime.Activity? = null
        synchronized(lifecycleLock) {
            if (state == State.STOPPED || state == State.STOPPING) {
                return
            }
            state = State.STOPPING
            SteamRuntime.get().stopHosting(this)
            activity = detachRuntimeActivity()
            state = State.STOPPED
        }
        closeActivity(activity)
    }

    fun openInviteOverlayAsync(): CompletableFuture<Void> {
        if (state != State.STARTED) {
            return CompletableFuture.failedFuture(
                    IllegalStateException("The Steam world is not ready for invitations")
            )
        }
        return try {
            SteamRuntime.get().openHostInviteOverlay(this)
        } catch (throwable: Throwable) {
            CompletableFuture.failedFuture<Void>(throwable)
        }
    }

    private fun start() {
        val runtime = SteamRuntime.get()
        try {
            val activity = runtime.acquireActivity()
            var canStart = false
            synchronized(lifecycleLock) {
                canStart = state == State.STARTING
                if (canStart) {
                    runtimeActivity = activity
                }
            }
            if (!canStart) {
                closeActivity(activity)
                return
            }

            runtime.awaitReady()
            val newAddress: SteamAddress
            val lobbyCreated: CompletableFuture<Long>
            synchronized(lifecycleLock) {
                if (state != State.STARTING) {
                    return
                }
                runtime.startHosting(
                        this,
                        localPortValue,
                        Config.INSTANCE.voiceChatPort.value(),
                        inviteToken,
                        accessModeValue
                )
                newAddress = SteamAddress(runtime.steamIdValue(), inviteToken)
                addressValue = newAddress
                lobbyCreated = runtime.createHostLobby(this, accessModeValue, newAddress)
            }

            // VPN routes can make CreateLobby time out while Steam itself is
            // still connected. The lobby manager retries sequentially, so
            // keep this session alive long enough for every attempt.
            lobbyCreated.get(HOST_LOBBY_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            synchronized(lifecycleLock) {
                if (state != State.STARTING) {
                    return
                }
                state = State.STARTED
            }

            E4steamClient.LOGGER.info(
                    "Steam LAN share ready for Steam user {}",
                    java.lang.Long.toUnsignedString(newAddress.steamId())
            )
            showReadyMessage(newAddress.inviteString())
        } catch (throwable: Throwable) {
            var activity: SteamRuntime.Activity? = null
            synchronized(lifecycleLock) {
                if (state != State.STARTING) {
                    return
                }
                failureCause = throwable
                state = State.UNHEALTHY
                runtime.stopHosting(this)
                activity = detachRuntimeActivity()
            }
            closeActivity(activity)
            E4steamClient.LOGGER.error("Could not start the Steam LAN share", throwable)
            showFailureMessage(throwable)
        }
    }

    private fun showReadyMessage(endpoint: String) {
        try {
            val message: Component
            if (accessModeValue == SteamAccessMode.INVITE_ONLY) {
                message = Mirror.translatable("text.e4steam_minecraft.privateLobbyReady")
            } else {
                val hidden = Config.INSTANCE.hideDomainInChat.value()
                val visible = if (hidden) {
                    Mirror.translatable("text.e4steam_minecraft.hiddenDomain")
                } else {
                    Mirror.literal(endpoint)
                }
                val clickableAddress = Mirror.withStyle(visible) { style ->
                    style.withColor(ChatFormatting.GREEN)
                            .withClickEvent(Mirror.copyToClipboard(endpoint))
                            .withHoverEvent(Mirror.showText(
                                    Mirror.translatable("text.e4steam_minecraft.addressCopyHelp")
                            ))
                }
                message = Mirror.translatable("text.e4steam_minecraft.domainAssigned", clickableAddress)
            }
            val stopButton = Mirror.withStyle(
                    Mirror.translatable("text.e4steam_minecraft.clickToStop")
            ) { style ->
                style.withColor(ChatFormatting.RED)
                        .withClickEvent(Mirror.runCommand("/e4steam stop"))
                        .withHoverEvent(Mirror.showText(
                                Mirror.translatable("text.e4steam_minecraft.stopSharingHelp")
                        ))
            }
            val inviteButton = Mirror.withStyle(
                    Mirror.translatable("text.e4steam_minecraft.inviteFriends")
            ) { style ->
                style.withColor(ChatFormatting.BLUE)
                        .withClickEvent(Mirror.runCommand("/e4steam invite"))
                        .withHoverEvent(Mirror.showText(
                                Mirror.translatable("text.e4steam_minecraft.inviteFriendsHelp")
                        ))
            }
            val readyMessage = Mirror.append(
                    Mirror.append(Mirror.append(message, Mirror.literal(" [")), inviteButton),
                    Mirror.append(Mirror.literal("] ["), Mirror.append(stopButton, Mirror.literal("]")))
            )
            Mirror.addMessageIf(readyMessage) {
                state == State.STARTED && E4steamClient.session === this
            }
        } catch (throwable: Throwable) {
            E4steamClient.LOGGER.warn("Steam share started, but its chat message could not be displayed", throwable)
        }
    }

    private fun showFailureMessage(throwable: Throwable) {
        try {
            val detail = throwable.message
            var message = Mirror.translatable("text.e4steam_minecraft.error")
            if (detail != null && !detail.isBlank()) {
                message = Mirror.append(message, Mirror.literal(": $detail"))
            }
            val retryButton = Mirror.withStyle(
                    Mirror.translatable("text.e4steam_minecraft.steamUnavailable")
            ) { style ->
                style.withClickEvent(Mirror.runCommand("/e4steam restart"))
            }
            val failureMessage = Mirror.append(
                    Mirror.append(message, Mirror.literal(" [")),
                    Mirror.append(retryButton, Mirror.literal("]"))
            )
            Mirror.addMessageIf(failureMessage) {
                state == State.UNHEALTHY && E4steamClient.session === this
            }
        } catch (displayFailure: Throwable) {
            E4steamClient.LOGGER.warn("Could not display the Steam initialization error in chat", displayFailure)
        }
    }

    fun runtimeFailed(throwable: Throwable) {
        var activity: SteamRuntime.Activity? = null
        synchronized(lifecycleLock) {
            if (state == State.STOPPED || state == State.STOPPING || state == State.UNHEALTHY) {
                return
            }
            failureCause = throwable
            state = State.UNHEALTHY
            SteamRuntime.get().stopHosting(this)
            activity = detachRuntimeActivity()
        }
        closeActivity(activity)
        showFailureMessage(throwable)
    }

    private fun detachRuntimeActivity(): SteamRuntime.Activity? {
        val activity = runtimeActivity
        runtimeActivity = null
        return activity
    }

    private fun closeActivity(activity: SteamRuntime.Activity?) {
        if (activity != null) {
            activity.close()
        }
    }

    enum class State {
        STARTING,
        STARTED,
        UNHEALTHY,
        STOPPING,
        STOPPED
    }

    companion object {
        private val SECURE_RANDOM = SecureRandom()
        private const val HOST_LOBBY_START_TIMEOUT_SECONDS = 75L
    }
}
