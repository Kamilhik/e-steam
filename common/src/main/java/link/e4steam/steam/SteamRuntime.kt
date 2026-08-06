package link.e4steam.steam

import com.codedisaster.steamworks.SteamID
import com.codedisaster.steamworks.SteamNativeHandle
import com.codedisaster.steamworks.SteamResult
import com.codedisaster.steamworks.SteamUser
import com.codedisaster.steamworks.SteamUserCallback
import com.codedisaster.steamworks.SteamUtils
import com.codedisaster.steamworks.SteamUtilsCallback
import link.e4steam.Agnos
import link.e4steam.E4steamClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns Steamworks for the Minecraft process. Every native networking call is
 * serialized on a single daemon thread.
 */
class SteamRuntime private constructor(api: SteamApi, installShutdownHook: Boolean) {
    private val lifecycleLock = Any()
    private val peerSessionLock = Any()
    private val steamLifecycle = SteamLifecycle(api)
    private val outbound = SteamOutboundQueue<SteamConnectionBridge>(
        MAX_OUTBOUND_PACKETS,
        MAX_OUTBOUND_DATA_PACKETS,
        MAX_OUTBOUND_DATAGRAM_PACKETS,
        MAX_OUTBOUND_OPEN_PACKETS,
        MAX_OUTBOUND_STANDALONE_RESETS
    )
    private val bridgeRegistry = SteamBridgeRegistry<SteamConnectionBridge, SteamUdpBridge>(MAX_ACTIVE_CONNECTIONS)
    private val pendingPeers = ConcurrentHashMap<Long, Long>()
    // long[0] = next check, long[1] = forced close. An array keeps this
    // state in SteamRuntime itself and avoids an extra lazy-loaded class on
    // Forge 1.17/1.18.
    private val idleSessionDeadlines = ConcurrentHashMap<Long, LongArray>()
    private val steamTasks = ConcurrentLinkedQueue<SteamTask<*>>()

    // A reliable packet rejected by Steam with temporary backpressure stays
    // ahead of the regular queue. Re-enqueuing it at the tail would reorder
    // Minecraft's TCP byte stream and corrupt the connection.
    private var retryOutboundPacket: SteamOutboundQueue.Packet<SteamConnectionBridge>? = null
    private var retryOutboundNotBeforeMillis: Long = 0
    private var retryOutboundDeadlineMillis: Long = 0

    @Volatile
    private var status: Status = Status.NEW
    @Volatile
    private var failureCause: Throwable? = null
    @Volatile
    private var localSteamId: Long = 0
    @Volatile
    private var workerThread: Thread? = null
    @Volatile
    private var generation: WorkerGeneration? = null
    @Volatile
    private var transport: SteamNetworkingMessagesTransport? = null
    @Volatile
    private var user: SteamUser? = null
    @Volatile
    private var utils: SteamUtils? = null
    @Volatile
    private var lobbyManager: SteamLobbyManager? = null
    @Volatile
    private var hostRegistration: HostRegistration? = null
    @Volatile
    private var nextLoopbackConnectAttemptAtMillis: Long = 0
    private var nextKnownPeerAcceptAtMillis: Long = 0
    private var permanentlyShutdown = false
    private var activityCount = 0

    init {
        if (installShutdownHook) {
            val shutdownHook = Thread({ shutdown() }, "e4steam-steam-shutdown")
            Runtime.getRuntime().addShutdownHook(shutdownHook)
        }
    }

    /**
     * Forge 1.17/1.18 may fail to resolve shaded Steamworks nested classes
     * when they are first requested later from the Steam callback thread.
     * Resolve them while the mod class path is being constructed instead.
     */
    @JvmStatic
    fun preloadCompatibilityClasses() {
        val names = arrayOf(
            "com.codedisaster.steamworks.SteamAPICall",
            "com.codedisaster.steamworks.SteamException",
            "com.codedisaster.steamworks.SteamFriends",
            "com.codedisaster.steamworks.SteamFriends\$FriendFlags",
            "com.codedisaster.steamworks.SteamFriends\$FriendGameInfo",
            "com.codedisaster.steamworks.SteamFriends\$FriendRelationship",
            "com.codedisaster.steamworks.SteamFriends\$OverlayDialog",
            "com.codedisaster.steamworks.SteamFriends\$OverlayToStoreFlag",
            "com.codedisaster.steamworks.SteamFriends\$OverlayToUserDialog",
            "com.codedisaster.steamworks.SteamFriends\$OverlayToWebPageMode",
            "com.codedisaster.steamworks.SteamFriends\$PersonaChange",
            "com.codedisaster.steamworks.SteamFriends\$PersonaState",
            "com.codedisaster.steamworks.SteamFriendsCallback",
            "com.codedisaster.steamworks.SteamFriendsCallbackAdapter",
            "com.codedisaster.steamworks.SteamFriendsNative",
            "com.codedisaster.steamworks.SteamID",
            "com.codedisaster.steamworks.SteamMatchmaking",
            "com.codedisaster.steamworks.SteamMatchmaking\$ChatEntry",
            "com.codedisaster.steamworks.SteamMatchmaking\$ChatEntryType",
            "com.codedisaster.steamworks.SteamMatchmaking\$ChatMemberStateChange",
            "com.codedisaster.steamworks.SteamMatchmaking\$ChatRoomEnterResponse",
            "com.codedisaster.steamworks.SteamMatchmaking\$LobbyComparison",
            "com.codedisaster.steamworks.SteamMatchmaking\$LobbyDistanceFilter",
            "com.codedisaster.steamworks.SteamMatchmaking\$LobbyType",
            "com.codedisaster.steamworks.SteamMatchmakingCallback",
            "com.codedisaster.steamworks.SteamMatchmakingCallbackAdapter",
            "com.codedisaster.steamworks.SteamMatchmakingGameServerItem",
            "com.codedisaster.steamworks.SteamMatchmakingKeyValuePair",
            "com.codedisaster.steamworks.SteamMatchmakingNative",
            "com.codedisaster.steamworks.SteamMatchmakingPingResponse",
            "com.codedisaster.steamworks.SteamMatchmakingPlayersResponse",
            "com.codedisaster.steamworks.SteamMatchmakingRulesResponse",
            "com.codedisaster.steamworks.SteamMatchmakingServerListResponse",
            "com.codedisaster.steamworks.SteamMatchmakingServerListResponse\$Response",
            "com.codedisaster.steamworks.SteamMatchmakingServerNetAdr",
            "com.codedisaster.steamworks.SteamMatchmakingServers",
            "com.codedisaster.steamworks.SteamMatchmakingServersNative"
        )
        val loader = SteamRuntime::class.java.classLoader
        for (name in names) {
            try {
                Class.forName(name, false, loader)
            } catch (error: ClassNotFoundException) {
                E4steamClient.LOGGER.warn("Could not preload Steam compatibility class {}", name, error)
            } catch (error: LinkageError) {
                E4steamClient.LOGGER.warn("Could not preload Steam compatibility class {}", name, error)
            }
        }
    }

    /**
     * Keeps the Steam API alive for one user-visible operation. Activities are
     * cheap, restart-safe leases and may be closed more than once.
     */
    fun acquireActivity(): Activity {
        synchronized(lifecycleLock) {
            if (permanentlyShutdown) {
                throw IllegalStateException("Steam runtime has been shut down")
            }
            activityCount++
            val current = generation
            if (current != null) {
                current.idleSinceMillis = 0
            }
            return Activity(this)
        }
    }

    @Throws(IOException::class)
    fun awaitReady() {
        if (!Agnos.isClient()) {
            throw IOException("This e4steam release supports integrated LAN worlds only")
        }
        val target = ensureWorkerStarted()
        try {
            target.ready.get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            synchronized(lifecycleLock) {
                if (generation !== target || target.stopRequested.get() || status != Status.RUNNING) {
                    throw IOException("Steam runtime stopped before it became usable (status: $status)")
                }
            }
        } catch (exception: TimeoutException) {
            throw IOException("Timed out while initializing Steam", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while initializing Steam", exception)
        } catch (exception: ExecutionException) {
            val cause = exception.cause ?: exception
            throw IOException("Steam initialization failed: " + cause.message, cause)
        }
    }

    fun statusSummary(): String {
        var summary = status.name.lowercase()
        if (status == Status.RUNNING) {
            summary += " (Steam client connected as " + steamId() + ")"
        }
        return summary
    }

    fun steamId(): String =
        if (localSteamId == 0L) "unavailable" else java.lang.Long.toUnsignedString(localSteamId)

    fun failureCause(): Throwable? = failureCause

    fun steamIdValue(): Long = localSteamId

    /**
     * Returns the authenticated Steam peer owning this exact Minecraft TCP
     * connection, or zero for ordinary LAN, unresolved, and stale sockets.
     */
    fun authenticatedMinecraftPeer(remoteAddress: SocketAddress): Long {
        val remotePort = SteamLoopbackAuthentication.loopbackPort(remoteAddress)
        if (remotePort < 0 || hostRegistration == null || status != Status.RUNNING) {
            return 0
        }
        for (bridge in bridgeRegistry.snapshot()) {
            if (bridge.isHostSide() && !bridge.isClosed() && bridge.localPort() == remotePort) {
                return bridge.remoteSteamId()
            }
        }
        return 0
    }

    @Throws(IOException::class)
    fun startHosting(
        owner: SteamSession,
        localPort: Int,
        udpPort: Int,
        token: ByteArray,
        accessMode: SteamAccessMode
    ) {
        awaitReady()
        if (localPort < 1 || localPort > 65535) {
            throw IOException("Invalid LAN port: $localPort")
        }
        if (accessMode == SteamAccessMode.LOCAL_ONLY) {
            throw IOException("Local-only mode does not start Steam hosting")
        }
        if (udpPort < 0 || udpPort > 65535) {
            throw IOException("Invalid UDP tunnel port: $udpPort")
        }

        val udpEndpoint = VoiceChatUdpEndpoint.resolve(localPort, udpPort)
        val replacement = HostRegistration(
            owner,
            localPort,
            udpEndpoint,
            token.clone(),
            accessMode
        )
        if (udpEndpoint.hostPort() > 0) {
            E4steamClient.LOGGER.info(
                "Using UDP port {} for {}",
                udpEndpoint.hostPort(),
                udpEndpoint.source()
            )
        }
        synchronized(lifecycleLock) {
            val current = hostRegistration
            if (current != null && current.owner !== owner) {
                throw IOException("Another Steam hosting session is still stopping")
            }
            hostRegistration = replacement
            nextLoopbackConnectAttemptAtMillis = 0
        }
    }

    fun stopHosting(owner: SteamSession) {
        var removed = false
        synchronized(lifecycleLock) {
            val current = hostRegistration
            if (current != null && current.owner === owner) {
                hostRegistration = null
                removed = true
            }
        }
        if (removed) {
            closeHostBridges(owner)
        }
        // Social state is authoritative for the Steam lobby. Always ask it
        // to stop this owner even if the local registration was already
        // removed during a race or worker failure.
        submitSteamTaskIfRunning {
            val current = lobbyManager
            if (current != null) {
                current.stopHosting(owner)
            }
        }
    }

    @Throws(IOException::class)
    fun createHostLobby(
        owner: SteamSession,
        accessMode: SteamAccessMode,
        address: SteamAddress
    ): CompletableFuture<Long> {
        awaitReady()
        val scheduled = submitSteamTask<CompletableFuture<Long>> {
            val current = lobbyManager
            if (current == null) {
                throw IOException("Steam social services are unavailable")
            }
            current.createHostLobby(owner, accessMode, address)
        }
        return scheduled.thenCompose { it }
    }

    @Throws(IOException::class)
    fun openHostInviteOverlay(owner: SteamSession): CompletableFuture<Void> {
        awaitReady()
        return submitSteamTask<Unit> {
            val current = lobbyManager
            if (current == null) {
                throw IOException("Steam social services are unavailable")
            }
            current.openHostInviteOverlay(owner)
            Unit
        }.thenApply { null as Void }
    }

    @Throws(IOException::class)
    fun openFriendsOverlay() {
        awaitReady()
        val task = submitSteamTask {
            val current = lobbyManager
            if (current == null) {
                throw IOException("Steam social services are unavailable")
            }
            current.openFriendsOverlay()
        }
        waitForSteamTask(task, STEAM_TASK_TIMEOUT, "opening the Steam friends overlay")
    }

    fun cancelGuestJoin() {
        submitSteamTaskIfRunning {
            val current = lobbyManager
            if (current != null) {
                current.cancelGuestJoin()
            }
        }
    }

    /**
     * Ends the restartable Steam generation when Minecraft is leaving the
     * e4steam path for an ordinary multiplayer server.
     */
    fun stopForDirectServerConnection() {
        SteamClientBridge.cancelPending()
        synchronized(lifecycleLock) {
            hostRegistration = null
        }
        for (bridge in bridgeRegistry.snapshot()) {
            bridge.close(false)
        }
        clearOutbound()
        pendingPeers.clear()
        idleSessionDeadlines.clear()

        synchronized(lifecycleLock) {
            val current = generation
            if (current != null) {
                current.stopRequested.set(true)
                status = Status.STOPPING
                current.worker?.interrupt()
            }
        }
    }

    fun beginGuestConnect(endpoint: String): CompletableFuture<Boolean> =
        submitSteamTaskIfRunning {
            val current = lobbyManager
            current != null && current.beginGuestConnect(endpoint)
        }

    fun claimGuestInvite(endpoint: String): CompletableFuture<Boolean> =
        submitSteamTaskIfRunning {
            val current = lobbyManager
            current != null && current.claimGuestInvite(endpoint)
        }

    fun nextConnectionId(remoteSteamId: Long): Int =
        bridgeRegistry.nextConnectionId(remoteSteamId, ThreadLocalRandom.current())

    @Throws(IOException::class)
    fun registerClientBridge(
        remoteSteamId: Long,
        connectionId: Int,
        socket: Socket,
        activity: Activity
    ): SteamConnectionBridge {
        verifyRunning()
        if (remoteSteamId == 0L) {
            throw IOException("Invalid host Steam ID: " + java.lang.Long.toUnsignedString(remoteSteamId))
        }

        val bridge = SteamConnectionBridge(
            this,
            remoteSteamId,
            connectionId,
            socket,
            null,
            activity
        )
        val key = SteamBridgeRegistry.Key(remoteSteamId, connectionId)
        val result = registerBridge(key, bridge)
        if (result != SteamBridgeRegistry.Registration.REGISTERED) {
            val reason = when (result) {
                SteamBridgeRegistry.Registration.CAPACITY -> "Too many active Steam bridges"
                SteamBridgeRegistry.Registration.COLLISION -> "Steam connection identifier collision"
                SteamBridgeRegistry.Registration.UNAVAILABLE -> "Steam runtime stopped while opening the bridge"
                else -> "Could not register the Steam bridge"
            }
            throw IOException(reason)
        }
        submitSteamTaskIfRunning {
            val current = lobbyManager
            if (current != null) {
                current.clientBridgeOpened(remoteSteamId)
            }
        }
        return bridge
    }

    fun sendOpen(bridge: SteamConnectionBridge, token: ByteArray): Boolean =
        enqueueControl(
            bridge.remoteSteamId(),
            bridge.connectionId(),
            SteamProtocol.encodeOpen(bridge.connectionId(), token),
            SteamOutboundQueue.Kind.OPEN,
            bridge
        )

    private fun sendOpenAck(bridge: SteamConnectionBridge, endpoint: VoiceChatUdpEndpoint): Boolean =
        enqueueControl(
            bridge.remoteSteamId(),
            bridge.connectionId(),
            SteamProtocol.encodeOpenAck(bridge.connectionId(), endpoint),
            SteamOutboundQueue.Kind.OPEN_ACK,
            bridge
        )

    fun sendData(bridge: SteamConnectionBridge, payload: ByteArray): Boolean =
        enqueueData(
            bridge,
            SteamProtocol.encodeData(bridge.connectionId(), payload)
        )

    fun sendDatagram(bridge: SteamUdpBridge, payload: ByteArray) {
        val owner = bridge.owner()
        val packet = SteamProtocol.encodeDatagram(owner.connectionId(), payload)
        if (status != Status.RUNNING
            || isWorkerStopping()
            || bridge.isClosed()
            || owner.isClosed()
        ) {
            return
        }
        outbound.offerDatagram(owner.remoteSteamId(), owner.connectionId(), packet, owner)
    }

    private fun startClientUdpBridge(owner: SteamConnectionBridge, endpoint: VoiceChatUdpEndpoint) {
        startUdpBridge(owner, endpoint.clientPort(owner.localPort()), false)
    }

    private fun startHostUdpBridge(owner: SteamConnectionBridge, endpoint: VoiceChatUdpEndpoint) {
        startUdpBridge(owner, endpoint.hostPort(), true)
    }

    private fun startUdpBridge(owner: SteamConnectionBridge, port: Int, hostSide: Boolean) {
        if (port == 0 || owner.isClosed()) {
            return
        }
        if (port < 1 || port > 65535) {
            E4steamClient.LOGGER.warn("UDP tunneling is disabled because port {} is invalid", port)
            return
        }

        val key = SteamBridgeRegistry.Key(
            owner.remoteSteamId(),
            owner.connectionId()
        )
        if (bridgeRegistry.containsUdp(key)) {
            return
        }
        var bridge: SteamUdpBridge? = null
        try {
            bridge = if (hostSide) {
                SteamUdpBridge.host(this, owner, port)
            } else {
                SteamUdpBridge.client(this, owner, port)
            }
            val previous = bridgeRegistry.putUdpIfAbsent(key, bridge)
            if (previous != null || owner.isClosed()) {
                bridge.close()
                return
            }
            bridge.start()
            E4steamClient.LOGGER.info(
                "Opened {} UDP tunnel on port {} for Steam user {}",
                if (hostSide) "host" else "client",
                port,
                java.lang.Long.toUnsignedString(owner.remoteSteamId())
            )
        } catch (exception: IOException) {
            bridge?.close()
            E4steamClient.LOGGER.warn(
                "Could not open the optional UDP tunnel on port {}; Minecraft TCP will continue",
                port,
                exception
            )
        }
    }

    fun closeUdpBridge(owner: SteamConnectionBridge) {
        val udp = bridgeRegistry.removeUdp(
            SteamBridgeRegistry.Key(owner.remoteSteamId(), owner.connectionId())
        )
        if (udp != null) {
            udp.close()
        }
    }

    fun sendFin(bridge: SteamConnectionBridge): Boolean =
        enqueueControl(
            bridge.remoteSteamId(),
            bridge.connectionId(),
            SteamProtocol.encodeFin(bridge.connectionId()),
            SteamOutboundQueue.Kind.FIN,
            bridge
        )

    fun sendReset(bridge: SteamConnectionBridge): Boolean =
        enqueueControl(
            bridge.remoteSteamId(),
            bridge.connectionId(),
            SteamProtocol.encodeReset(bridge.connectionId()),
            SteamOutboundQueue.Kind.RESET,
            bridge
        )

    private fun sendStandaloneReset(remoteSteamId: Long, connectionId: Int) {
        enqueueControl(
            remoteSteamId,
            connectionId,
            SteamProtocol.encodeReset(connectionId),
            SteamOutboundQueue.Kind.RESET,
            null
        )
        synchronized(peerSessionLock) {
            if (!hasBridgeForRemote(remoteSteamId)) {
                idleSessionDeadlines.put(remoteSteamId, newIdleSessionDeadline())
            }
        }
    }

    fun unregister(bridge: SteamConnectionBridge) {
        closeUdpBridge(bridge)
        purgeOutbound(bridge)
        var removed = false
        var anotherBridgeExists = false
        synchronized(peerSessionLock) {
            if (bridgeRegistry.remove(
                    SteamBridgeRegistry.Key(bridge.remoteSteamId(), bridge.connectionId()),
                    bridge
                )
            ) {
                removed = true
                anotherBridgeExists = if (bridge.isHostSide()) {
                    hasBridgeForRemote(bridge.remoteSteamId())
                } else {
                    hasClientBridgeForRemote(bridge.remoteSteamId())
                }
                if (!hasBridgeForRemote(bridge.remoteSteamId())) {
                    idleSessionDeadlines.put(bridge.remoteSteamId(), newIdleSessionDeadline())
                }
            }
        }
        if (removed && !bridge.isHostSide()) {
            val finalAnotherBridgeExists = anotherBridgeExists
            submitSteamTaskIfRunning {
                val current = lobbyManager
                if (current != null) {
                    current.clientBridgeClosed(bridge.remoteSteamId(), finalAnotherBridgeExists)
                }
            }
        }
        bridge.releaseActivity()
    }

    fun shutdown() {
        var target: WorkerGeneration? = null
        synchronized(lifecycleLock) {
            if (permanentlyShutdown) {
                return
            }
            permanentlyShutdown = true
            val current = generation
            if (current != null) {
                current.stopRequested.set(true)
                status = Status.STOPPING
            }
            target = current
        }

        SteamClientBridge.cancelPending()
        hostRegistration = null
        for (bridge in bridgeRegistry.snapshot()) {
            bridge.close(false)
        }
        clearOutbound()
        pendingPeers.clear()
        idleSessionDeadlines.clear()

        val worker = target?.worker
        if (worker != null) {
            worker.interrupt()
            if (worker !== Thread.currentThread()) {
                try {
                    worker.join(2000)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } else {
            status = Status.STOPPED
        }
    }

    @Throws(IOException::class)
    private fun ensureWorkerStarted(): WorkerGeneration {
        synchronized(lifecycleLock) {
            val deadline = System.currentTimeMillis() + START_TIMEOUT.toMillis()
            while (generation?.stopRequested?.get() == true) {
                if (permanentlyShutdown) {
                    throw IOException("Steam runtime has been shut down")
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    throw IOException("Timed out while waiting for the previous Steam runtime to stop")
                }
                try {
                    lifecycleLock.wait(Math.min(remaining, 250))
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for Steam to restart", exception)
                }
            }
            if (permanentlyShutdown) {
                throw IOException("Steam runtime has been shut down")
            }
            val existing = generation
            if (existing != null) {
                return existing
            }

            failureCause = null
            localSteamId = 0
            status = Status.STARTING
            val created = WorkerGeneration()
            val worker = Thread({ runWorker(created) }, "e4steam-steam-runtime")
            worker.isDaemon = true
            created.worker = worker
            generation = created
            workerThread = worker
            worker.start()
            return created
        }
    }

    private fun runWorker(currentGeneration: WorkerGeneration) {
        var workerFailure: Throwable? = null
        try {
            initializeSteam()
            synchronized(lifecycleLock) {
                if (generation !== currentGeneration || currentGeneration.stopRequested.get()) {
                    throw IOException("Steam runtime was stopped during initialization")
                }
                status = Status.RUNNING
            }
            currentGeneration.ready.complete(null)
            E4steamClient.LOGGER.info(
                "Steam Networking Messages initialized as {} using App ID {}",
                steamId(),
                APP_ID
            )

            val sendBuffer = ByteBuffer.allocateDirect(SteamProtocol.MAX_PACKET_SIZE)
            val receiveBuffer = ByteBuffer.allocateDirect(SteamProtocol.MAX_ACCEPTED_STEAM_PACKET_SIZE)

            while (!currentGeneration.stopRequested.get()) {
                if (!steamLifecycle.isRunning()) {
                    throw IOException("Steam disconnected while e4steam was active")
                }
                steamLifecycle.runCallbacks()
                drainSteamTasks()
                acceptKnownPeerSessions(System.currentTimeMillis())
                drainOutbound(sendBuffer)
                receivePackets(receiveBuffer)
                cleanupPeerSessions()
                val currentSocial = lobbyManager
                if (currentSocial != null) {
                    currentSocial.cleanup(System.currentTimeMillis())
                }
                if (shouldStopForIdle(currentGeneration, System.currentTimeMillis())) {
                    break
                }
                try {
                    Thread.sleep(10)
                } catch (exception: InterruptedException) {
                    // Wake-ups are used for queued Steam tasks and lifecycle changes.
                }
            }
        } catch (throwable: Throwable) {
            workerFailure = throwable
            failureCause = throwable
            synchronized(lifecycleLock) {
                status = Status.FAILED
            }
            currentGeneration.ready.completeExceptionally(throwable)
            E4steamClient.LOGGER.error("Steam runtime failed", throwable)
        } finally {
            var failedHost: HostRegistration? = null
            synchronized(lifecycleLock) {
                failedHost = hostRegistration
                hostRegistration = null
            }
            val failedBridges = bridgeRegistry.snapshot()
            for (bridge in failedBridges) {
                bridge.close(false)
            }
            // A bridge that had already queued RESET is closed but still
            // registered. Explicit unregistration is required here so its
            // capacity permit and optional Activity survive no restart.
            for (bridge in failedBridges) {
                unregister(bridge)
            }
            bridgeRegistry.clear()
            clearOutbound()
            pendingPeers.clear()
            idleSessionDeadlines.clear()

            if (workerFailure != null && failedHost != null) {
                failedHost.owner.runtimeFailed(
                    workerFailure
                )
            }

            val currentSocial = lobbyManager
            lobbyManager = null
            if (currentSocial != null) {
                try {
                    currentSocial.close()
                } catch (ignored: Throwable) {
                }
            }

            val currentTransport = transport
            transport = null
            if (currentTransport != null) {
                try {
                    currentTransport.close()
                } catch (ignored: Throwable) {
                }
            }
            val currentUser = user
            user = null
            if (currentUser != null) {
                try {
                    currentUser.dispose()
                } catch (ignored: Throwable) {
                }
            }
            val currentUtils = utils
            utils = null
            if (currentUtils != null) {
                try {
                    currentUtils.dispose()
                } catch (ignored: Throwable) {
                }
            }
            try {
                steamLifecycle.close()
            } catch (ignored: Throwable) {
            }
            failPendingSteamTasks(
                workerFailure ?: IOException("Steam runtime stopped")
            )
            localSteamId = 0
            synchronized(lifecycleLock) {
                if (generation === currentGeneration) {
                    generation = null
                    workerThread = null
                }
                if (workerFailure == null) {
                    status = Status.STOPPED
                }
                lifecycleLock.notifyAll()
            }
        }
    }

    private fun shouldStopForIdle(currentGeneration: WorkerGeneration, nowMillis: Long): Boolean {
        synchronized(lifecycleLock) {
            if (permanentlyShutdown || generation !== currentGeneration) {
                currentGeneration.stopRequested.set(true)
                status = Status.STOPPING
                return true
            }

            val currentSocial = lobbyManager
            val keepAlive = activityCount > 0
                || hostRegistration != null
                || !bridgeRegistry.isEmpty()
                || !outbound.isEmpty()
                || !idleSessionDeadlines.isEmpty()
                || !steamTasks.isEmpty()
                || (currentSocial != null && currentSocial.keepsRuntimeAlive())
            if (keepAlive) {
                currentGeneration.idleSinceMillis = 0
                return false
            }
            if (currentGeneration.idleSinceMillis == 0L) {
                currentGeneration.idleSinceMillis = nowMillis
                return false
            }
            if (nowMillis - currentGeneration.idleSinceMillis < RUNTIME_IDLE_SHUTDOWN_MILLIS) {
                return false
            }

            status = Status.STOPPING
            currentGeneration.stopRequested.set(true)
            return true
        }
    }

    private fun <T> submitSteamTask(action: () -> T): CompletableFuture<T> {
        val task = SteamTask(action)
        synchronized(lifecycleLock) {
            val current = generation
            if (current == null
                || current.stopRequested.get()
                || status != Status.RUNNING
                || permanentlyShutdown
            ) {
                throw IOException("Steam runtime is not available for this operation")
            }
            steamTasks.add(task)
            current.idleSinceMillis = 0
            current.worker?.interrupt()
        }
        return task.result
    }

    private fun <T> submitSteamTaskIfRunning(action: () -> T): CompletableFuture<T> =
        try {
            submitSteamTask(action)
        } catch (exception: IOException) {
            CompletableFuture.failedFuture(exception)
        }

    private fun drainSteamTasks() {
        for (handled in 0 until 256) {
            val task = steamTasks.poll() ?: return
            task.run()
        }
    }

    private fun failPendingSteamTasks(cause: Throwable) {
        while (true) {
            val task = steamTasks.poll() ?: break
            task.fail(cause)
        }
    }

    @Throws(IOException::class)
    private fun <T> waitForSteamTask(task: CompletableFuture<T>, timeout: Duration, operation: String): T {
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (exception: TimeoutException) {
            throw IOException("Timed out while $operation", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while $operation", exception)
        } catch (exception: ExecutionException) {
            val cause = exception.cause ?: exception
            if (cause is IOException) {
                throw cause
            }
            throw IOException("Steam failed while $operation: " + cause.message, cause)
        }
    }

    private fun releaseActivity() {
        synchronized(lifecycleLock) {
            if (activityCount > 0) {
                activityCount--
            }
            val current = generation
            if (current != null) {
                current.worker?.interrupt()
            }
        }
    }

    fun isOverlayEnabledOnWorker(): Boolean {
        val current = utils
        return current != null && current.isOverlayEnabled()
    }

    private fun isWorkerStopping(): Boolean {
        val current = generation
        return permanentlyShutdown || current == null || current.stopRequested.get()
    }

    private fun initializeSteam() {
        ensureAppIdFile()

        steamLifecycle.start()

        val createdUtils = SteamUtils(object : SteamUtilsCallback {
        })
        val initializedAppId = createdUtils.appID
        if (initializedAppId != APP_ID) {
            createdUtils.dispose()
            throw IOException(
                "Steam initialized the Minecraft process with App ID $initializedAppId"
                    + " instead of the required App ID $APP_ID"
            )
        }
        utils = createdUtils

        val createdUser = SteamUser(object : SteamUserCallback {
        })
        val id = createdUser.steamID
        if (id == null || !id.isValid()) {
            createdUser.dispose()
            throw IOException("Steam returned an invalid user ID")
        }

        localSteamId = SteamNativeHandle.getNativeHandle(id)
        user = createdUser
        val apiPath = steamLifecycle.steamApiPath()
            ?: throw IllegalStateException("Steam lifecycle is not running")
        transport = SteamNetworkingMessagesTransport.open(
            apiPath,
            object : SteamNetworkingMessagesTransport.SessionListener {
                override fun onSessionRequest(remoteId: Long) {
                    E4steamClient.LOGGER.debug(
                        "Steam Networking Messages session requested by {}",
                        java.lang.Long.toUnsignedString(remoteId)
                    )
                    val current = transport
                    if (current == null) {
                        return
                    }
                    synchronized(peerSessionLock) {
                        val currentSocial = lobbyManager
                        if (!hasBridgeForRemote(remoteId)
                            && (currentSocial == null || !currentSocial.mayAcceptPeer(remoteId))
                        ) {
                            current.closePeer(remoteId)
                            return
                        }
                        if (!hasBridgeForRemote(remoteId) && pendingPeers.size >= MAX_PENDING_PEERS) {
                            current.closePeer(remoteId)
                            return
                        }
                        if (!current.accept(remoteId)) {
                            current.closePeer(remoteId)
                            return
                        }
                        if (!hasBridgeForRemote(remoteId)) {
                            pendingPeers.put(
                                remoteId,
                                System.currentTimeMillis() + PENDING_PEER_TIMEOUT_MILLIS
                            )
                        }
                    }
                }

                override fun onSessionFailed(remoteId: Long, endReason: Int, detail: String) {
                    if (detail.isBlank()) {
                        E4steamClient.LOGGER.warn(
                            "Steam Networking Messages session with {} failed (reason {})",
                            java.lang.Long.toUnsignedString(remoteId),
                            endReason
                        )
                    } else {
                        E4steamClient.LOGGER.warn(
                            "Steam Networking Messages session with {} failed (reason {}): {}",
                            java.lang.Long.toUnsignedString(remoteId),
                            endReason,
                            detail
                        )
                    }
                    var failedBridges: List<SteamConnectionBridge> = emptyList()
                    synchronized(peerSessionLock) {
                        pendingPeers.remove(remoteId)
                        idleSessionDeadlines.remove(remoteId)
                        failedBridges = bridgeRegistry.snapshot()
                            .filter { bridge -> bridge.remoteSteamId() == remoteId }
                    }
                    val current = transport
                    if (current != null) {
                        current.closePeer(remoteId)
                    }
                    for (bridge in failedBridges) {
                        bridge.close(false)
                    }
                }
            }
        )
        lobbyManager = SteamLobbyManager(this)
    }

    @Throws(IOException::class)
    private fun ensureAppIdFile() {
        val appIdFile = Path.of(System.getProperty("user.dir"), "steam_appid.txt").toAbsolutePath().normalize()
        if (Files.exists(appIdFile)) {
            val value = Files.readString(appIdFile, StandardCharsets.US_ASCII).trim()
            if (!Integer.toString(APP_ID).equals(value)) {
                throw IOException(
                    "Refusing to overwrite $appIdFile; expected App ID 480 but found '$value'"
                )
            }
            return
        }

        Files.writeString(
            appIdFile,
            Integer.toString(APP_ID) + System.lineSeparator(),
            StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )
        E4steamClient.LOGGER.info("Created {} for Steam App ID {}", appIdFile, APP_ID)
    }

    @Throws(IOException::class)
    private fun verifyRunning() {
        if (status != Status.RUNNING || transport == null || isWorkerStopping()) {
            throw IOException("Steam runtime is not running (status: $status)")
        }
    }

    private fun enqueueData(bridge: SteamConnectionBridge, packet: ByteArray): Boolean {
        if (status != Status.RUNNING || isWorkerStopping() || bridge.isClosed()) {
            return false
        }
        return outbound.offerData(
            bridge.remoteSteamId(),
            bridge.connectionId(),
            packet,
            bridge
        )
    }

    private fun enqueueControl(
        remoteSteamId: Long,
        connectionId: Int,
        packet: ByteArray,
        kind: SteamOutboundQueue.Kind,
        bridge: SteamConnectionBridge?
    ): Boolean {
        if (status != Status.RUNNING || isWorkerStopping()) {
            return false
        }
        if (bridge != null && kind != SteamOutboundQueue.Kind.RESET && bridge.isClosed()) {
            return false
        }
        return outbound.offerControl(remoteSteamId, connectionId, packet, kind, bridge)
    }

    private fun drainOutbound(buffer: ByteBuffer) {
        val current = requireNotNull(transport)
        for (sent in 0 until MAX_PACKETS_PER_TICK) {
            val now = System.currentTimeMillis()
            var packet = retryOutboundPacket
            if (packet != null && now < retryOutboundNotBeforeMillis) {
                return
            }
            if (packet == null) {
                packet = outbound.poll()
            }
            if (packet == null) {
                return
            }

            val key = SteamBridgeRegistry.Key(
                packet.remoteSteamId(),
                packet.connectionId()
            )
            val currentBridge = bridgeRegistry.get(key)
            if (!isPacketCurrent(packet, currentBridge)) {
                clearRetriedPacket(packet)
                continue
            }

            buffer.clear()
            buffer.put(packet.payload()).flip()
            val result = current.sendResult(
                packet.remoteSteamId(),
                buffer,
                packet.kind() == SteamOutboundQueue.Kind.DATAGRAM,
                CHANNEL
            )
            val accepted = result == 1
            val packetBridge = packet.bridge()
            if (accepted) {
                clearRetriedPacket(packet)
            }
            if (packet.kind() == SteamOutboundQueue.Kind.RESET && packetBridge != null) {
                packetBridge.markResetSubmitted()
            } else if (accepted && packet.kind() == SteamOutboundQueue.Kind.FIN && packetBridge != null) {
                packetBridge.markFinSubmitted()
            } else if (!accepted && packetBridge != null && packet.kind() != SteamOutboundQueue.Kind.DATAGRAM) {
                val failure = SteamResult.byValue(result)
                if (isRetryableSendFailure(failure)) {
                    if (retryOutboundPacket !== packet) {
                        retryOutboundPacket = packet
                        retryOutboundDeadlineMillis = now + OUTBOUND_SEND_RETRY_TIMEOUT_MILLIS
                        E4steamClient.LOGGER.debug(
                            "Steam applied outbound backpressure for {}; preserving and retrying {}",
                            java.lang.Long.toUnsignedString(packet.remoteSteamId()),
                            packet.kind()
                        )
                    }
                    if (now < retryOutboundDeadlineMillis) {
                        retryOutboundNotBeforeMillis = now + OUTBOUND_SEND_RETRY_DELAY_MILLIS
                        return
                    }
                    clearRetriedPacket(packet)
                }
                E4steamClient.LOGGER.warn(
                    "Steam Networking Messages send to {} failed for {}: {} ({})",
                    java.lang.Long.toUnsignedString(packet.remoteSteamId()),
                    packet.kind(),
                    failure,
                    result
                )
                packetBridge.close(false)
            }
        }
    }

    private fun acceptKnownPeerSessions(now: Long) {
        if (now < nextKnownPeerAcceptAtMillis) {
            return
        }
        nextKnownPeerAcceptAtMillis = now + KNOWN_PEER_ACCEPT_INTERVAL_MILLIS
        val currentSocial = lobbyManager
        val currentTransport = transport
        if (currentSocial == null || currentTransport == null) {
            return
        }
        currentSocial.forEachKnownSessionPeer { remoteSteamId ->
            synchronized(peerSessionLock) {
                if (hasBridgeForRemote(remoteSteamId)
                    || pendingPeers.containsKey(remoteSteamId)
                    || pendingPeers.size >= MAX_PENDING_PEERS
                ) {
                    return@forEachKnownSessionPeer
                }
                if (currentTransport.accept(remoteSteamId)) {
                    pendingPeers.put(
                        remoteSteamId,
                        System.currentTimeMillis() + PENDING_PEER_TIMEOUT_MILLIS
                    )
                    E4steamClient.LOGGER.debug(
                        "Accepted Steam session for known lobby peer {}",
                        java.lang.Long.toUnsignedString(remoteSteamId)
                    )
                }
            }
        }
    }

    private fun clearOutbound() {
        outbound.clear()
        retryOutboundPacket = null
        retryOutboundNotBeforeMillis = 0
        retryOutboundDeadlineMillis = 0
    }

    private fun purgeOutbound(bridge: SteamConnectionBridge) {
        outbound.purge(bridge)
        val retry = retryOutboundPacket
        if (retry != null && retry.bridge() === bridge) {
            clearRetriedPacket(retry)
        }
    }

    private fun clearRetriedPacket(packet: SteamOutboundQueue.Packet<SteamConnectionBridge>) {
        if (retryOutboundPacket === packet) {
            retryOutboundPacket = null
            retryOutboundNotBeforeMillis = 0
            retryOutboundDeadlineMillis = 0
        }
    }

    private fun isPacketCurrent(
        packet: SteamOutboundQueue.Packet<SteamConnectionBridge>,
        currentBridge: SteamConnectionBridge?
    ): Boolean {
        val packetBridge = packet.bridge()
        if (packetBridge == null) {
            // A standalone RESET rejects an OPEN that never created a bridge.
            return packet.kind() == SteamOutboundQueue.Kind.RESET && currentBridge == null
        }
        if (currentBridge !== packetBridge) {
            return false
        }
        return packet.kind() == SteamOutboundQueue.Kind.RESET || !packetBridge.isClosed()
    }

    private fun receivePackets(buffer: ByteBuffer) {
        val current = requireNotNull(transport)
        for (received in 0 until MAX_PACKETS_PER_TICK) {
            val size = current.availablePacketSize(CHANNEL)
            if (size == 0) {
                return
            }

            if (size <= 0 || size > SteamProtocol.MAX_ACCEPTED_STEAM_PACKET_SIZE) {
                throw IOException("Steam reported an invalid P2P packet size: $size")
            }

            buffer.clear()
            val packet = current.receive(buffer, CHANNEL)
            val read = packet.size()
            if (read <= 0) {
                continue
            }
            if (read > SteamProtocol.MAX_PACKET_SIZE) {
                continue // Foreign App ID 480 traffic; consume and ignore it.
            }
            if (packet.remoteSteamId() == 0L) {
                continue // Steam API peers must have an authenticated Steam identity.
            }

            buffer.position(0)
            buffer.limit(read)
            val frame = SteamProtocol.decode(buffer)
            if (frame == null) {
                continue // App ID 480 is shared, so unrelated traffic is expected.
            }
            dispatchFrame(packet.remoteSteamId(), frame)
        }
    }

    private fun dispatchFrame(remoteSteamId: Long, frame: SteamProtocol.Frame) {
        val key = SteamBridgeRegistry.Key(remoteSteamId, frame.connectionId())
        when (frame.type()) {
            SteamProtocol.OPEN -> handleOpen(remoteSteamId, key, frame.payload())
            SteamProtocol.OPEN_ACK -> handleOpenAck(key, frame.payload())
            SteamProtocol.DATA -> {
                val bridge = bridgeRegistry.get(key)
                if (bridge != null) {
                    bridge.acceptSteamData(frame.payload())
                }
            }
            SteamProtocol.FIN -> {
                val bridge = bridgeRegistry.get(key)
                if (bridge != null) {
                    bridge.acceptRemoteFin()
                }
            }
            SteamProtocol.RESET -> {
                val bridge = bridgeRegistry.get(key)
                if (bridge != null) {
                    bridge.resetFromRemote()
                }
            }
            SteamProtocol.DATAGRAM -> {
                val bridge = bridgeRegistry.getUdp(key)
                if (bridge != null) {
                    bridge.acceptSteamDatagram(frame.payload())
                }
            }
            else -> {
            }
        }
    }

    private fun handleOpenAck(key: SteamBridgeRegistry.Key, payload: ByteArray) {
        val bridge = bridgeRegistry.get(key)
        if (bridge == null || bridge.isHostSide() || bridge.isClosed()) {
            return
        }
        val buffer = ByteBuffer.wrap(payload)
        val clientPortMode = buffer.get()
        val hostPort = Short.toUnsignedInt(buffer.short)
        try {
            startClientUdpBridge(
                bridge,
                VoiceChatUdpEndpoint.fromHandshake(hostPort, clientPortMode)
            )
        } catch (exception: IllegalArgumentException) {
            bridge.close(true)
        }
    }

    private fun handleOpen(remoteSteamId: Long, key: SteamBridgeRegistry.Key, token: ByteArray) {
        val registration = hostRegistration
        val currentSocial = lobbyManager
        val peerAllowed = registration != null
            && currentSocial != null
            && currentSocial.allows(registration.owner, remoteSteamId)
        val authorization = SteamInvitationAuthorizer.authorize(
            registration?.token,
            token,
            peerAllowed
        )
        if (authorization != SteamInvitationAuthorizer.Decision.ALLOWED) {
            sendStandaloneReset(remoteSteamId, key.connectionId())
            return
        }
        val activeRegistration = registration!!
        synchronized(peerSessionLock) {
            pendingPeers.remove(remoteSteamId)
            idleSessionDeadlines.remove(remoteSteamId)
        }
        if (bridgeRegistry.contains(key)) {
            return
        }
        val activeHostConnections = bridgeRegistry.count(
            { bridge -> bridge.isHostedBy(activeRegistration.owner) && !bridge.isClosed() }
        )
        if (activeHostConnections >= SteamLobbyManager.VANILLA_MAX_GUESTS.toLong()) {
            sendStandaloneReset(remoteSteamId, key.connectionId())
            return
        }
        if (System.currentTimeMillis() < nextLoopbackConnectAttemptAtMillis) {
            sendStandaloneReset(remoteSteamId, key.connectionId())
            return
        }

        val socket = Socket()
        var handedOff = false
        try {
            socket.connect(
                InetSocketAddress("127.0.0.1", activeRegistration.localPort),
                LOOPBACK_CONNECT_TIMEOUT_MILLIS
            )
            socket.setTcpNoDelay(true)
            socket.setKeepAlive(true)
            nextLoopbackConnectAttemptAtMillis = 0

            if (hostRegistration !== registration || status != Status.RUNNING || isWorkerStopping()) {
                sendStandaloneReset(remoteSteamId, key.connectionId())
                return
            }

            val bridge = SteamConnectionBridge(
                this,
                remoteSteamId,
                key.connectionId(),
                socket,
                activeRegistration.owner,
                null
            )
            val result = registerBridge(key, bridge)
            if (result != SteamBridgeRegistry.Registration.REGISTERED) {
                if (result != SteamBridgeRegistry.Registration.COLLISION) {
                    sendStandaloneReset(remoteSteamId, key.connectionId())
                }
                return
            }
            handedOff = true
            if (hostRegistration !== registration) {
                bridge.close(true)
                return
            }
            startHostUdpBridge(bridge, activeRegistration.udpEndpoint)
            if (!sendOpenAck(bridge, activeRegistration.udpEndpoint)) {
                bridge.close(true)
                return
            }
            bridge.start()
            E4steamClient.LOGGER.info(
                "Accepted Steam bridge from {}",
                java.lang.Long.toUnsignedString(remoteSteamId)
            )
        } catch (exception: IOException) {
            nextLoopbackConnectAttemptAtMillis =
                System.currentTimeMillis() + LOOPBACK_FAILURE_BACKOFF_MILLIS
            sendStandaloneReset(remoteSteamId, key.connectionId())
            E4steamClient.LOGGER.warn("Could not connect a Steam guest to the local LAN server", exception)
        } finally {
            if (!handedOff) {
                try {
                    socket.close()
                } catch (ignored: IOException) {
                }
            }
        }
    }

    private fun closeHostBridges(owner: SteamSession) {
        for (bridge in bridgeRegistry.snapshot()) {
            if (bridge.isHostedBy(owner)) {
                bridge.close(true)
            }
        }
    }

    fun closeRemoteBridges(remoteSteamId: Long) {
        for (bridge in bridgeRegistry.snapshot()) {
            if (bridge.remoteSteamId() == remoteSteamId) {
                bridge.close(true)
            }
        }
    }

    private fun registerBridge(
        key: SteamBridgeRegistry.Key,
        bridge: SteamConnectionBridge
    ): SteamBridgeRegistry.Registration {
        synchronized(peerSessionLock) {
            val result = bridgeRegistry.register(key, bridge) {
                status == Status.RUNNING && !isWorkerStopping()
            }
            if (result != SteamBridgeRegistry.Registration.REGISTERED) {
                return result
            }
            pendingPeers.remove(key.remoteSteamId())
            idleSessionDeadlines.remove(key.remoteSteamId())
            return SteamBridgeRegistry.Registration.REGISTERED
        }
    }

    private fun hasBridgeForRemote(remoteSteamId: Long): Boolean =
        bridgeRegistry.any { bridge -> bridge.remoteSteamId() == remoteSteamId }

    fun hasClientBridgeForRemote(remoteSteamId: Long): Boolean =
        bridgeRegistry.any { bridge -> !bridge.isHostSide() && bridge.remoteSteamId() == remoteSteamId }

    private fun closeSteamSessionIfIdle(remoteSteamId: Long) {
        synchronized(peerSessionLock) {
            if (!hasBridgeForRemote(remoteSteamId)) {
                pendingPeers.remove(remoteSteamId)
                idleSessionDeadlines.remove(remoteSteamId)
                closeSteamSession(remoteSteamId)
            }
        }
    }

    private fun cleanupPeerSessions() {
        val now = System.currentTimeMillis()
        pendingPeers.forEach { remoteSteamId, deadline ->
            if (deadline <= now) {
                synchronized(peerSessionLock) {
                    if (!hasBridgeForRemote(remoteSteamId)
                        && pendingPeers.remove(remoteSteamId, deadline)
                    ) {
                        closeSteamSession(remoteSteamId)
                    }
                }
            }
        }
        idleSessionDeadlines.forEach { remoteSteamId, deadline ->
            if (deadline[0] <= now) {
                synchronized(peerSessionLock) {
                    if (idleSessionDeadlines.get(remoteSteamId) !== deadline) {
                        return@forEach
                    }
                    if (hasBridgeForRemote(remoteSteamId)) {
                        idleSessionDeadlines.remove(remoteSteamId)
                    } else if (now < deadline[1]
                        && hasQueuedSteamPackets(remoteSteamId)
                    ) {
                        idleSessionDeadlines.put(
                            remoteSteamId,
                            longArrayOf(now + IDLE_SESSION_RECHECK_MILLIS, deadline[1])
                        )
                    } else {
                        idleSessionDeadlines.remove(remoteSteamId)
                        closeSteamSession(remoteSteamId)
                    }
                }
            }
        }
    }

    private fun newIdleSessionDeadline(): LongArray {
        val now = System.currentTimeMillis()
        return longArrayOf(
            now + IDLE_SESSION_CLOSE_DELAY_MILLIS,
            now + IDLE_SESSION_MAX_DRAIN_MILLIS
        )
    }

    private fun hasQueuedSteamPackets(remoteSteamId: Long): Boolean {
        val current = transport
        if (current == null) {
            return false
        }
        return current.hasQueuedPackets(remoteSteamId)
    }

    private fun closeSteamSession(remoteSteamId: Long) {
        val current = transport
        if (current != null) {
            current.closePeer(remoteSteamId)
        }
    }

    /** A restart-safe lease that keeps Spacewar/Steamworks active while needed. */
    class Activity(private val runtime: SteamRuntime) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runtime.releaseActivity()
            }
        }
    }

    private class WorkerGeneration {
        val ready = CompletableFuture<Void>()
        val stopRequested = AtomicBoolean()
        @Volatile
        var worker: Thread? = null
        var idleSinceMillis: Long = 0
    }

    private class SteamTask<T>(private val action: () -> T) {
        val result = CompletableFuture<T>()

        fun run() {
            try {
                result.complete(action())
            } catch (throwable: Throwable) {
                result.completeExceptionally(throwable)
            }
        }

        fun fail(throwable: Throwable) {
            result.completeExceptionally(throwable)
        }
    }

    private class HostRegistration(
        val owner: SteamSession,
        val localPort: Int,
        val udpEndpoint: VoiceChatUdpEndpoint,
        val token: ByteArray,
        val accessMode: SteamAccessMode
    )

    private enum class Status {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED,
        STOPPED
    }

    companion object {
        private const val APP_ID = 480
        private const val CHANNEL = 480
        // Category limits leave room for terminal frames while preventing UDP
        // voice traffic from starving Minecraft's reliable TCP stream.
        private const val MAX_OUTBOUND_PACKETS = 2048
        private const val MAX_OUTBOUND_DATA_PACKETS = 1408
        private const val MAX_OUTBOUND_DATAGRAM_PACKETS = 384
        private const val MAX_OUTBOUND_OPEN_PACKETS = 64
        private const val MAX_OUTBOUND_STANDALONE_RESETS = 64
        // Yield regularly to the per-bridge localhost writer threads. Draining
        // hundreds of 32 KiB packets in one worker iteration can fill a bridge's
        // bounded inbound queue before its writer gets scheduled, which looks
        // like an infinitely falling player followed by a disconnect.
        private const val MAX_PACKETS_PER_TICK = 32
        private const val OUTBOUND_SEND_RETRY_DELAY_MILLIS = 25L
        private const val OUTBOUND_SEND_RETRY_TIMEOUT_MILLIS = 30_000L
        private const val MAX_ACTIVE_CONNECTIONS = 64
        private const val MAX_PENDING_PEERS = 64
        private const val PENDING_PEER_TIMEOUT_MILLIS = 10_000L
        private const val IDLE_SESSION_CLOSE_DELAY_MILLIS = 250L
        private const val IDLE_SESSION_RECHECK_MILLIS = 100L
        private const val IDLE_SESSION_MAX_DRAIN_MILLIS = 2_000L
        private const val LOOPBACK_CONNECT_TIMEOUT_MILLIS = 100
        private const val LOOPBACK_FAILURE_BACKOFF_MILLIS = 2_000L
        private const val START_TIMEOUT = Duration.ofSeconds(30)
        private const val STEAM_TASK_TIMEOUT = Duration.ofSeconds(10)
        private const val RUNTIME_IDLE_SHUTDOWN_MILLIS = 1_000L
        private const val KNOWN_PEER_ACCEPT_INTERVAL_MILLIS = 100L

        private val INSTANCE = SteamRuntime(SteamworksApi(), true)

        @JvmStatic
        fun get(): SteamRuntime = INSTANCE

        @JvmStatic
        fun isRetryableSendFailure(result: SteamResult): Boolean =
            result == SteamResult.LimitExceeded
                || result == SteamResult.Busy
                || result == SteamResult.NoConnection
                || result == SteamResult.ServiceUnavailable
    }
}
