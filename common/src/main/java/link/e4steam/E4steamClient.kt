package link.e4steam

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.exceptions.CommandSyntaxException
import link.e4steam.steam.SteamAccessMode
import link.e4steam.steam.SteamAddress
import link.e4steam.steam.SteamRuntime
import link.e4steam.steam.SteamSession
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.commands.BanListCommands
import net.minecraft.server.commands.BanPlayerCommands
import net.minecraft.server.commands.PardonCommand
import net.minecraft.server.commands.WhitelistCommand
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletionException

object E4steamClient {
    const val MOD_ID = "e4steam"

    @Volatile
    @JvmField
    var session: SteamSession? = null

    @Volatile
    @JvmField
    var selectedAccessMode: SteamAccessMode = SteamAccessMode.FRIENDS_ONLY

    @JvmField
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    @JvmStatic
    fun init() {
        Config.INSTANCE.id() // Touch to initialize for McQoy
        SteamRuntime.preloadCompatibilityClasses()
    }

    @JvmStatic
    fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
        if (Config.INSTANCE.restoreDedicatedCommands.value() && Agnos.isClient()) {
            BanListCommands.register(dispatcher)
            BanPlayerCommands.register(dispatcher)
            PardonCommand.register(dispatcher)
            WhitelistCommand.register(dispatcher)
        }
        dispatcher.register(
            Commands.literal("e4steam")
                .requires { src ->
                    val server = src.server
                    if (server == null) {
                        false
                    } else if (server.isDedicatedServer) {
                        src.hasPermission(4)
                    } else {
                        try {
                            Mirror.isSingleplayerOwner(server, src.getPlayerOrException())
                        } catch (e: CommandSyntaxException) {
                            false
                        }
                    }
                }
                .then(Commands.literal("stop").executes { ctx ->
                    val current = session
                    if (current != null
                        && current.state != SteamSession.State.STOPPED
                        && current.state != SteamSession.State.STOPPING) {
                        showStopConfirmation(ctx.source, current)
                    } else {
                        Mirror.sendFailureToSource(
                            ctx.source,
                            Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed")
                        )
                    }
                    1
                })
                .then(Commands.literal("start").executes { ctx ->
                    val current = session
                    if (current == null) {
                        Mirror.sendFailureToSource(
                            ctx.source,
                            Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed")
                        )
                        0
                    } else if (current.state != SteamSession.State.STOPPED
                        && current.state != SteamSession.State.UNHEALTHY) {
                        Mirror.sendFailureToSource(
                            ctx.source,
                            Mirror.translatable("text.e4steam_minecraft.serverAlreadyStarted")
                        )
                        0
                    } else {
                        current.stop()
                        replaceAndStartSession(current)
                        Mirror.sendSuccessToSource(
                            ctx.source,
                            Mirror.translatable("text.e4steam_minecraft.startSharing")
                        )
                        1
                    }
                })
                .then(Commands.literal("doctor").executes { ctx ->
                    val thread = Thread({
                        LOGGER.info("generating e4steam doctor report")
                        Mirror.sendSuccessToSource(
                            ctx.source,
                            Mirror.translatable("text.e4steam_minecraft.doctor.start")
                        )
                        val diag = Doctor.doctor()
                        LOGGER.info("e4steam doctor report:\n{}", diag)
                        Mirror.sendSuccessToSource(ctx.source, Mirror.literal(diag))
                    }, "e4steam-steam-doctor")
                    thread.isDaemon = true
                    thread.start()
                    1
                })
                .then(Commands.literal("invite").executes { ctx ->
                    val current = session
                    if (current == null || current.state != SteamSession.State.STARTED) {
                        Mirror.sendFailureToSource(
                            ctx.source,
                            Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed")
                        )
                        0
                    } else {
                        val source = ctx.source
                        current.openInviteOverlayAsync().whenComplete { _, throwable ->
                            source.server!!.execute {
                                if (throwable == null) {
                                    Mirror.sendSuccessToSource(
                                        source,
                                        Mirror.translatable("text.e4steam_minecraft.inviteFriends")
                                    )
                                } else {
                                    val cause = unwrapCompletionException(throwable)
                                    LOGGER.warn("Could not open the Steam invitation overlay", cause)
                                    Mirror.sendFailureToSource(
                                        source,
                                        Mirror.translatable("text.e4steam_minecraft.overlayUnavailable")
                                    )
                                }
                            }
                        }
                        1
                    }
                })
                .then(Commands.literal("restart").executes { ctx ->
                    val current = session
                    if (current != null) {
                        current.stop()
                        replaceAndStartSession(current)
                    } else {
                        Mirror.sendFailureToSource(
                            ctx.source,
                            Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed")
                        )
                    }
                    1
                })
        )
    }

    private fun showStopConfirmation(source: CommandSourceStack, requestedSession: SteamSession) {
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            val previousScreen = minecraft.screen
            minecraft.setScreen(
                ConfirmScreen(
                    { confirmed ->
                        minecraft.setScreen(previousScreen)
                        if (confirmed) {
                            source.server!!.execute {
                                if (session != requestedSession
                                    || requestedSession.state == SteamSession.State.STOPPED
                                    || requestedSession.state == SteamSession.State.STOPPING) {
                                    Mirror.sendFailureToSource(
                                        source,
                                        Mirror.translatable("text.e4steam_minecraft.serverAlreadyClosed")
                                    )
                                    return@execute
                                }
                                requestedSession.stop()
                                Mirror.sendSuccessToSource(
                                    source,
                                    Mirror.translatable("text.e4steam_minecraft.closeServer")
                                )
                            }
                        }
                    },
                    Mirror.translatable("text.e4steam_minecraft.stopConfirmTitle"),
                    Mirror.translatable("text.e4steam_minecraft.stopConfirmMessage"),
                    Mirror.translatable("text.e4steam_minecraft.stopConfirmYes"),
                    Mirror.translatable("text.e4steam_minecraft.stopConfirmNo")
                )
            )
        }
    }

    private fun replaceAndStartSession(previous: SteamSession) {
        val replacement = SteamSession(previous.localPort(), previous.accessMode())
        session = replacement
        replacement.startAsync()
    }

    /** Stops Spacewar before Minecraft connects to a regular, non-e4steam server. */
    @JvmStatic
    fun stopSteamForDirectServerConnection() {
        val current = session
        if (current != null) {
            current.stop()
            if (session == current) {
                session = null
            }
        }
        SteamRuntime.get().stopForDirectServerConnection()
    }

    /** Called by the Steam callback thread after a validated lobby invitation was accepted. */
    @JvmStatic
    fun acceptSteamInvite(endpoint: String, hostName: String) {
        if (SteamAddress.tryParse(endpoint).isEmpty()) {
            showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinInvalidAddress"))
            return
        }

        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            val displayName = normalizedHostName(hostName)
            if (minecraft.screen is ConnectScreen) {
                SteamRuntime.get().cancelGuestJoin()
                minecraft.gui.chat.addMessage(
                    Mirror.translatable("text.e4steam_minecraft.joinAlreadyConnecting")
                )
                return@execute
            }
            if (minecraft.level == null) {
                val parent = currentOrMultiplayerScreen(minecraft)
                claimSteamInviteAndConnect(minecraft, endpoint, displayName, parent, null, false)
                return@execute
            }

            val previousScreen = minecraft.screen
            val title = Mirror.translatable("text.e4steam_minecraft.joinInviteTitle")
            val message = Mirror.translatable("text.e4steam_minecraft.joinInviteMessage", displayName)
            minecraft.setScreen(
                ConfirmScreen(
                    { confirmed ->
                        if (!confirmed) {
                            SteamRuntime.get().cancelGuestJoin()
                            minecraft.setScreen(previousScreen)
                        } else {
                            val returnScreen = multiplayerScreen()
                            minecraft.setScreen(
                                MinecraftUiCompat.messageScreen(
                                    Mirror.translatable("connect.connecting"),
                                    previousScreen
                                )
                            )
                            claimSteamInviteAndConnect(
                                minecraft,
                                endpoint,
                                displayName,
                                returnScreen,
                                previousScreen,
                                true
                            )
                        }
                    },
                    title,
                    message,
                    Mirror.translatable("text.e4steam_minecraft.joinInviteConfirm"),
                    Mirror.translatable("text.e4steam_minecraft.joinInviteStay")
                )
            )
        }
    }

    /** Displays an invitation/join error without touching Minecraft UI from a Steam callback thread. */
    @JvmStatic
    fun showSteamJoinFailure(detail: String?) {
        var reason = Mirror.translatable("text.e4steam_minecraft.connectionError")
        if (detail != null && detail.isNotBlank()) {
            reason = Mirror.append(reason, Mirror.literal(": $detail"))
        }
        showSteamJoinFailure(reason)
    }

    /** Displays a localized invitation/join error on the Minecraft thread. */
    @JvmStatic
    fun showSteamJoinFailure(reason: Component) {
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            if (minecraft.level != null || minecraft.screen is ConnectScreen) {
                minecraft.gui.chat.addMessage(reason)
                return@execute
            }

            val parent = currentOrMultiplayerScreen(minecraft)
            minecraft.setScreen(
                DisconnectedScreen(
                    parent,
                    Mirror.translatable("connect.failed"),
                    reason
                )
            )
        }
    }

    private fun connectToSteamHost(
        minecraft: Minecraft,
        endpoint: String,
        hostName: String,
        parent: Screen
    ) {
        try {
            MinecraftUiCompat.connect(
                parent,
                minecraft,
                ServerAddress.parseString(endpoint),
                hostName,
                endpoint
            )
        } catch (throwable: Throwable) {
            LOGGER.error("Could not begin connecting to a Steam invitation", throwable)
            SteamRuntime.get().cancelGuestJoin()
            showSteamJoinFailure(throwable.message)
        }
    }

    private fun claimSteamInviteAndConnect(
        minecraft: Minecraft,
        endpoint: String,
        hostName: String,
        parent: Screen,
        rejectionScreen: Screen?,
        disconnectCurrent: Boolean
    ) {
        val claim = if (disconnectCurrent) {
            SteamRuntime.get().claimGuestInvite(endpoint)
        } else {
            SteamRuntime.get().beginGuestConnect(endpoint)
        }
        claim.whenComplete { claimed, throwable ->
            minecraft.execute {
                if (throwable != null || claimed != true) {
                    rejectSteamInvite(minecraft, rejectionScreen, throwable)
                    return@execute
                }

                if (disconnectCurrent && minecraft.level != null) {
                    try {
                        MinecraftUiCompat.disconnect(minecraft, parent)
                    } catch (disconnectFailure: ReflectiveOperationException) {
                        rejectSteamInvite(minecraft, rejectionScreen, disconnectFailure)
                        return@execute
                    }
                }
                if (!disconnectCurrent) {
                    connectToSteamHost(minecraft, endpoint, hostName, parent)
                    return@execute
                }

                // Integrated-server shutdown can block while the world is
                // saved. Start the 30-second connection window only after
                // that completes, and revalidate that the lobby survived.
                SteamRuntime.get().beginGuestConnect(endpoint).whenComplete { armed, armFailure ->
                    minecraft.execute {
                        if (armFailure != null || armed != true) {
                            rejectSteamInvite(minecraft, null, armFailure)
                            return@execute
                        }
                        connectToSteamHost(minecraft, endpoint, hostName, parent)
                    }
                }
            }
        }
    }

    private fun rejectSteamInvite(minecraft: Minecraft, rejectionScreen: Screen?, throwable: Throwable?) {
        if (throwable != null) {
            LOGGER.warn("Could not claim the Steam invitation", unwrapCompletionException(throwable))
        }
        if (minecraft.level != null) {
            minecraft.setScreen(rejectionScreen)
        }
        showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinExpired"))
    }

    private fun currentOrMultiplayerScreen(minecraft: Minecraft): Screen =
        minecraft.screen ?: multiplayerScreen()

    private fun multiplayerScreen(): Screen = JoinMultiplayerScreen(TitleScreen())

    private fun normalizedHostName(hostName: String?): String {
        if (hostName == null || hostName.isBlank()) {
            return Mirror.translatable("text.e4steam_minecraft.steamFriend").getString()
        }
        val normalized = hostName.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").strip()
        if (normalized.isEmpty()) {
            return Mirror.translatable("text.e4steam_minecraft.steamFriend").getString()
        }
        return if (normalized.length <= 64) normalized else normalized.substring(0, 64)
    }

    private fun unwrapCompletionException(throwable: Throwable): Throwable {
        var current = throwable
        while (current is CompletionException && current.cause != null) {
            current = current.cause!!
        }
        return current
    }
}
