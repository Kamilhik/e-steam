package link.e4steam.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUserCallback;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;
import link.e4steam.Agnos;
import link.e4steam.E4steamClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Owns Steamworks for the Minecraft process. Every native networking call is
 * serialized on a single daemon thread.
 */
public final class SteamRuntime {
    private static final int APP_ID = 480;
    private static final int CHANNEL = 480;
    // Category limits leave room for terminal frames while preventing UDP
    // voice traffic from starving Minecraft's reliable TCP stream.
    private static final int MAX_OUTBOUND_PACKETS = 2048;
    private static final int MAX_OUTBOUND_DATA_PACKETS = 1408;
    private static final int MAX_OUTBOUND_DATAGRAM_PACKETS = 384;
    private static final int MAX_OUTBOUND_OPEN_PACKETS = 64;
    private static final int MAX_OUTBOUND_STANDALONE_RESETS = 64;
    // Yield regularly to the per-bridge localhost writer threads. Draining
    // hundreds of 32 KiB packets in one worker iteration can fill a bridge's
    // bounded inbound queue before its writer gets scheduled, which looks
    // like an infinitely falling player followed by a disconnect.
    private static final int MAX_PACKETS_PER_TICK = 32;
    private static final long OUTBOUND_SEND_RETRY_DELAY_MILLIS = 25;
    private static final long OUTBOUND_SEND_RETRY_TIMEOUT_MILLIS = 30_000;
    private static final int MAX_ACTIVE_CONNECTIONS = 64;
    private static final int MAX_PENDING_PEERS = 64;
    private static final long PENDING_PEER_TIMEOUT_MILLIS = 10_000;
    private static final long IDLE_SESSION_CLOSE_DELAY_MILLIS = 250;
    private static final long IDLE_SESSION_RECHECK_MILLIS = 100;
    private static final long IDLE_SESSION_MAX_DRAIN_MILLIS = 2_000;
    private static final int LOOPBACK_CONNECT_TIMEOUT_MILLIS = 100;
    private static final long LOOPBACK_FAILURE_BACKOFF_MILLIS = 2_000;
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STEAM_TASK_TIMEOUT = Duration.ofSeconds(10);
    private static final long RUNTIME_IDLE_SHUTDOWN_MILLIS = 1_000;
    private static final long KNOWN_PEER_ACCEPT_INTERVAL_MILLIS = 100;

    private static final SteamRuntime INSTANCE = new SteamRuntime(new SteamworksApi(), true);

    private final Object lifecycleLock = new Object();
    private final Object peerSessionLock = new Object();
    private final SteamLifecycle steamLifecycle;
    private final SteamOutboundQueue<SteamConnectionBridge> outbound = new SteamOutboundQueue<>(
            MAX_OUTBOUND_PACKETS,
            MAX_OUTBOUND_DATA_PACKETS,
            MAX_OUTBOUND_DATAGRAM_PACKETS,
            MAX_OUTBOUND_OPEN_PACKETS,
            MAX_OUTBOUND_STANDALONE_RESETS
    );
    private final SteamBridgeRegistry<SteamConnectionBridge, SteamUdpBridge> bridgeRegistry =
            new SteamBridgeRegistry<>(MAX_ACTIVE_CONNECTIONS);
    private final ConcurrentHashMap<Long, Long> pendingPeers = new ConcurrentHashMap<>();
    // long[0] = next check, long[1] = forced close. An array keeps this
    // state in SteamRuntime itself and avoids an extra lazy-loaded class on
    // Forge 1.17/1.18.
    private final ConcurrentHashMap<Long, long[]> idleSessionDeadlines = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SteamTask<?>> steamTasks = new ConcurrentLinkedQueue<>();

    // A reliable packet rejected by Steam with temporary backpressure stays
    // ahead of the regular queue. Re-enqueuing it at the tail would reorder
    // Minecraft's TCP byte stream and corrupt the connection.
    private SteamOutboundQueue.Packet<SteamConnectionBridge> retryOutboundPacket;
    private long retryOutboundNotBeforeMillis;
    private long retryOutboundDeadlineMillis;

    private volatile Status status = Status.NEW;
    private volatile Throwable failureCause;
    private volatile long localSteamId;
    private volatile Thread workerThread;
    private volatile WorkerGeneration generation;
    private volatile SteamNetworkingMessagesTransport transport;
    private volatile SteamUser user;
    private volatile SteamUtils utils;
    private volatile SteamLobbyManager lobbyManager;
    private volatile HostRegistration hostRegistration;
    private volatile long nextLoopbackConnectAttemptAtMillis;
    private long nextKnownPeerAcceptAtMillis;
    private boolean permanentlyShutdown;
    private int activityCount;

    SteamRuntime(SteamApi api, boolean installShutdownHook) {
        steamLifecycle = new SteamLifecycle(api);
        if (installShutdownHook) {
            Thread shutdownHook = new Thread(this::shutdown, "e4steam-steam-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    public static SteamRuntime get() {
        return INSTANCE;
    }

    /**
     * Forge 1.17/1.18 may fail to resolve shaded Steamworks nested classes
     * when they are first requested later from the Steam callback thread.
     * Resolve them while the mod class path is being constructed instead.
     */
    public static void preloadCompatibilityClasses() {
        String[] names = {
                "com.codedisaster.steamworks.SteamAPICall",
                "com.codedisaster.steamworks.SteamException",
                "com.codedisaster.steamworks.SteamFriends",
                "com.codedisaster.steamworks.SteamFriends$FriendFlags",
                "com.codedisaster.steamworks.SteamFriends$FriendGameInfo",
                "com.codedisaster.steamworks.SteamFriends$FriendRelationship",
                "com.codedisaster.steamworks.SteamFriends$OverlayDialog",
                "com.codedisaster.steamworks.SteamFriends$OverlayToStoreFlag",
                "com.codedisaster.steamworks.SteamFriends$OverlayToUserDialog",
                "com.codedisaster.steamworks.SteamFriends$OverlayToWebPageMode",
                "com.codedisaster.steamworks.SteamFriends$PersonaChange",
                "com.codedisaster.steamworks.SteamFriends$PersonaState",
                "com.codedisaster.steamworks.SteamFriendsCallback",
                "com.codedisaster.steamworks.SteamFriendsCallbackAdapter",
                "com.codedisaster.steamworks.SteamFriendsNative",
                "com.codedisaster.steamworks.SteamID",
                "com.codedisaster.steamworks.SteamMatchmaking",
                "com.codedisaster.steamworks.SteamMatchmaking$ChatEntry",
                "com.codedisaster.steamworks.SteamMatchmaking$ChatEntryType",
                "com.codedisaster.steamworks.SteamMatchmaking$ChatMemberStateChange",
                "com.codedisaster.steamworks.SteamMatchmaking$ChatRoomEnterResponse",
                "com.codedisaster.steamworks.SteamMatchmaking$LobbyComparison",
                "com.codedisaster.steamworks.SteamMatchmaking$LobbyDistanceFilter",
                "com.codedisaster.steamworks.SteamMatchmaking$LobbyType",
                "com.codedisaster.steamworks.SteamMatchmakingCallback",
                "com.codedisaster.steamworks.SteamMatchmakingCallbackAdapter",
                "com.codedisaster.steamworks.SteamMatchmakingGameServerItem",
                "com.codedisaster.steamworks.SteamMatchmakingKeyValuePair",
                "com.codedisaster.steamworks.SteamMatchmakingNative",
                "com.codedisaster.steamworks.SteamMatchmakingPingResponse",
                "com.codedisaster.steamworks.SteamMatchmakingPlayersResponse",
                "com.codedisaster.steamworks.SteamMatchmakingRulesResponse",
                "com.codedisaster.steamworks.SteamMatchmakingServerListResponse",
                "com.codedisaster.steamworks.SteamMatchmakingServerListResponse$Response",
                "com.codedisaster.steamworks.SteamMatchmakingServerNetAdr",
                "com.codedisaster.steamworks.SteamMatchmakingServers",
                "com.codedisaster.steamworks.SteamMatchmakingServersNative"
        };
        ClassLoader loader = SteamRuntime.class.getClassLoader();
        for (String name : names) {
            try {
                Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError error) {
                E4steamClient.LOGGER.warn("Could not preload Steam compatibility class {}", name, error);
            }
        }
    }

    /**
     * Keeps the Steam API alive for one user-visible operation. Activities are
     * cheap, restart-safe leases and may be closed more than once.
     */
    public Activity acquireActivity() {
        synchronized (lifecycleLock) {
            if (permanentlyShutdown) {
                throw new IllegalStateException("Steam runtime has been shut down");
            }
            activityCount++;
            WorkerGeneration current = generation;
            if (current != null) {
                current.idleSinceMillis = 0;
            }
            return new Activity(this);
        }
    }

    public void awaitReady() throws IOException {
        if (!Agnos.isClient()) {
            throw new IOException("This e4steam release supports integrated LAN worlds only");
        }
        WorkerGeneration target = ensureWorkerStarted();
        try {
            target.ready.get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            synchronized (lifecycleLock) {
                if (generation != target || target.stopRequested.get() || status != Status.RUNNING) {
                    throw new IOException("Steam runtime stopped before it became usable (status: " + status + ")");
                }
            }
        } catch (TimeoutException exception) {
            throw new IOException("Timed out while initializing Steam", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while initializing Steam", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IOException("Steam initialization failed: " + cause.getMessage(), cause);
        }
    }

    public String statusSummary() {
        String summary = status.name().toLowerCase();
        if (status == Status.RUNNING) {
            summary += " (Steam client connected as " + steamId() + ")";
        }
        return summary;
    }

    public String steamId() {
        return localSteamId == 0 ? "unavailable" : Long.toUnsignedString(localSteamId);
    }

    public Throwable failureCause() {
        return failureCause;
    }

    long steamIdValue() {
        return localSteamId;
    }

    /**
     * Returns the authenticated Steam peer owning this exact Minecraft TCP
     * connection, or zero for ordinary LAN, unresolved, and stale sockets.
     */
    public long authenticatedMinecraftPeer(SocketAddress remoteAddress) {
        int remotePort = SteamLoopbackAuthentication.loopbackPort(remoteAddress);
        if (remotePort < 0 || hostRegistration == null || status != Status.RUNNING) {
            return 0;
        }
        for (SteamConnectionBridge bridge : bridgeRegistry.snapshot()) {
            if (bridge.isHostSide() && !bridge.isClosed() && bridge.localPort() == remotePort) {
                return bridge.remoteSteamId();
            }
        }
        return 0;
    }

    void startHosting(
            SteamSession owner,
            int localPort,
            int udpPort,
            byte[] token,
            SteamAccessMode accessMode
    ) throws IOException {
        awaitReady();
        if (localPort < 1 || localPort > 65535) {
            throw new IOException("Invalid LAN port: " + localPort);
        }
        if (accessMode == SteamAccessMode.LOCAL_ONLY) {
            throw new IOException("Local-only mode does not start Steam hosting");
        }
        if (udpPort < 0 || udpPort > 65535) {
            throw new IOException("Invalid UDP tunnel port: " + udpPort);
        }

        VoiceChatUdpEndpoint udpEndpoint = VoiceChatUdpEndpoint.resolve(localPort, udpPort);
        HostRegistration replacement = new HostRegistration(
                owner,
                localPort,
                udpEndpoint,
                token.clone(),
                accessMode
        );
        if (udpEndpoint.hostPort() > 0) {
            E4steamClient.LOGGER.info(
                    "Using UDP port {} for {}",
                    udpEndpoint.hostPort(),
                    udpEndpoint.source()
            );
        }
        synchronized (lifecycleLock) {
            HostRegistration current = hostRegistration;
            if (current != null && current.owner() != owner) {
                throw new IOException("Another Steam hosting session is still stopping");
            }
            hostRegistration = replacement;
            nextLoopbackConnectAttemptAtMillis = 0;
        }
    }

    void stopHosting(SteamSession owner) {
        boolean removed = false;
        synchronized (lifecycleLock) {
            HostRegistration current = hostRegistration;
            if (current != null && current.owner() == owner) {
                hostRegistration = null;
                removed = true;
            }
        }
        if (removed) {
            closeHostBridges(owner);
        }
        // Social state is authoritative for the Steam lobby. Always ask it
        // to stop this owner even if the local registration was already
        // removed during a race or worker failure.
        submitSteamTaskIfRunning(() -> {
            SteamLobbyManager current = lobbyManager;
            if (current != null) {
                current.stopHosting(owner);
            }
            return null;
        });
    }

    CompletableFuture<Long> createHostLobby(
            SteamSession owner,
            SteamAccessMode accessMode,
            SteamAddress address
    ) throws IOException {
        awaitReady();
        CompletableFuture<CompletableFuture<Long>> scheduled = submitSteamTask(() -> {
            SteamLobbyManager current = lobbyManager;
            if (current == null) {
                throw new IOException("Steam social services are unavailable");
            }
            return current.createHostLobby(owner, accessMode, address);
        });
        return scheduled.thenCompose(Function.identity());
    }

    CompletableFuture<Void> openHostInviteOverlay(SteamSession owner) throws IOException {
        awaitReady();
        return submitSteamTask(() -> {
            SteamLobbyManager current = lobbyManager;
            if (current == null) {
                throw new IOException("Steam social services are unavailable");
            }
            current.openHostInviteOverlay(owner);
            return null;
        });
    }

    public void openFriendsOverlay() throws IOException {
        awaitReady();
        CompletableFuture<Void> task = submitSteamTask(() -> {
            SteamLobbyManager current = lobbyManager;
            if (current == null) {
                throw new IOException("Steam social services are unavailable");
            }
            current.openFriendsOverlay();
            return null;
        });
        waitForSteamTask(task, STEAM_TASK_TIMEOUT, "opening the Steam friends overlay");
    }

    public void cancelGuestJoin() {
        submitSteamTaskIfRunning(() -> {
            SteamLobbyManager current = lobbyManager;
            if (current != null) {
                current.cancelGuestJoin();
            }
            return null;
        });
    }

    /**
     * Ends the restartable Steam generation when Minecraft is leaving the
     * e4steam path for an ordinary multiplayer server.
     */
    public void stopForDirectServerConnection() {
        SteamClientBridge.cancelPending();
        synchronized (lifecycleLock) {
            hostRegistration = null;
        }
        for (SteamConnectionBridge bridge : bridgeRegistry.snapshot()) {
            bridge.close(false);
        }
        clearOutbound();
        pendingPeers.clear();
        idleSessionDeadlines.clear();

        synchronized (lifecycleLock) {
            WorkerGeneration current = generation;
            if (current != null) {
                current.stopRequested.set(true);
                status = Status.STOPPING;
                current.worker.interrupt();
            }
        }
    }

    public CompletableFuture<Boolean> beginGuestConnect(String endpoint) {
        return submitSteamTaskIfRunning(() -> {
            SteamLobbyManager current = lobbyManager;
            return current != null && current.beginGuestConnect(endpoint);
        });
    }

    public CompletableFuture<Boolean> claimGuestInvite(String endpoint) {
        return submitSteamTaskIfRunning(() -> {
            SteamLobbyManager current = lobbyManager;
            return current != null && current.claimGuestInvite(endpoint);
        });
    }

    int nextConnectionId(long remoteSteamId) {
        return bridgeRegistry.nextConnectionId(remoteSteamId, ThreadLocalRandom.current());
    }

    SteamConnectionBridge registerClientBridge(
            long remoteSteamId,
            int connectionId,
            Socket socket,
            Activity activity
    ) throws IOException {
        verifyRunning();
        if (remoteSteamId == 0) {
            throw new IOException("Invalid host Steam ID: " + Long.toUnsignedString(remoteSteamId));
        }

        SteamConnectionBridge bridge = new SteamConnectionBridge(
                this,
                remoteSteamId,
                connectionId,
                socket,
                null,
                activity
        );
        SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(remoteSteamId, connectionId);
        SteamBridgeRegistry.Registration result = registerBridge(key, bridge);
        if (result != SteamBridgeRegistry.Registration.REGISTERED) {
            String reason = switch (result) {
                case CAPACITY -> "Too many active Steam bridges";
                case COLLISION -> "Steam connection identifier collision";
                case UNAVAILABLE -> "Steam runtime stopped while opening the bridge";
                default -> "Could not register the Steam bridge";
            };
            throw new IOException(reason);
        }
        submitSteamTaskIfRunning(() -> {
            SteamLobbyManager current = lobbyManager;
            if (current != null) {
                current.clientBridgeOpened(remoteSteamId);
            }
            return null;
        });
        return bridge;
    }

    boolean sendOpen(SteamConnectionBridge bridge, byte[] token) {
        return enqueueControl(
                bridge.remoteSteamId(),
                bridge.connectionId(),
                SteamProtocol.encodeOpen(bridge.connectionId(), token),
                SteamOutboundQueue.Kind.OPEN,
                bridge
        );
    }

    private boolean sendOpenAck(SteamConnectionBridge bridge, VoiceChatUdpEndpoint endpoint) {
        return enqueueControl(
                bridge.remoteSteamId(),
                bridge.connectionId(),
                SteamProtocol.encodeOpenAck(bridge.connectionId(), endpoint),
                SteamOutboundQueue.Kind.OPEN_ACK,
                bridge
        );
    }

    boolean sendData(SteamConnectionBridge bridge, byte[] payload) {
        return enqueueData(
                bridge,
                SteamProtocol.encodeData(bridge.connectionId(), payload)
        );
    }

    void sendDatagram(SteamUdpBridge bridge, byte[] payload) {
        SteamConnectionBridge owner = bridge.owner();
        byte[] packet = SteamProtocol.encodeDatagram(owner.connectionId(), payload);
        if (status != Status.RUNNING
                || isWorkerStopping()
                || bridge.isClosed()
                || owner.isClosed()) {
            return;
        }
        outbound.offerDatagram(owner.remoteSteamId(), owner.connectionId(), packet, owner);
    }

    private void startClientUdpBridge(SteamConnectionBridge owner, VoiceChatUdpEndpoint endpoint) {
        startUdpBridge(owner, endpoint.clientPort(owner.localPort()), false);
    }

    private void startHostUdpBridge(SteamConnectionBridge owner, VoiceChatUdpEndpoint endpoint) {
        startUdpBridge(owner, endpoint.hostPort(), true);
    }

    private void startUdpBridge(SteamConnectionBridge owner, int port, boolean hostSide) {
        if (port == 0 || owner.isClosed()) {
            return;
        }
        if (port < 1 || port > 65535) {
            E4steamClient.LOGGER.warn("UDP tunneling is disabled because port {} is invalid", port);
            return;
        }

        SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(
                owner.remoteSteamId(),
                owner.connectionId()
        );
        if (bridgeRegistry.containsUdp(key)) {
            return;
        }
        SteamUdpBridge bridge = null;
        try {
            bridge = hostSide
                    ? SteamUdpBridge.host(this, owner, port)
                    : SteamUdpBridge.client(this, owner, port);
            SteamUdpBridge previous = bridgeRegistry.putUdpIfAbsent(key, bridge);
            if (previous != null || owner.isClosed()) {
                bridge.close();
                return;
            }
            bridge.start();
            E4steamClient.LOGGER.info(
                    "Opened {} UDP tunnel on port {} for Steam user {}",
                    hostSide ? "host" : "client",
                    port,
                    Long.toUnsignedString(owner.remoteSteamId())
            );
        } catch (IOException exception) {
            if (bridge != null) {
                bridge.close();
            }
            E4steamClient.LOGGER.warn(
                    "Could not open the optional UDP tunnel on port {}; Minecraft TCP will continue",
                    port,
                    exception
            );
        }
    }

    void closeUdpBridge(SteamConnectionBridge owner) {
        SteamUdpBridge udp = bridgeRegistry.removeUdp(
                new SteamBridgeRegistry.Key(owner.remoteSteamId(), owner.connectionId())
        );
        if (udp != null) {
            udp.close();
        }
    }

    boolean sendFin(SteamConnectionBridge bridge) {
        return enqueueControl(
                bridge.remoteSteamId(),
                bridge.connectionId(),
                SteamProtocol.encodeFin(bridge.connectionId()),
                SteamOutboundQueue.Kind.FIN,
                bridge
        );
    }

    boolean sendReset(SteamConnectionBridge bridge) {
        return enqueueControl(
                bridge.remoteSteamId(),
                bridge.connectionId(),
                SteamProtocol.encodeReset(bridge.connectionId()),
                SteamOutboundQueue.Kind.RESET,
                bridge
        );
    }

    private void sendStandaloneReset(long remoteSteamId, int connectionId) {
        enqueueControl(
                remoteSteamId,
                connectionId,
                SteamProtocol.encodeReset(connectionId),
                SteamOutboundQueue.Kind.RESET,
                null
        );
        synchronized (peerSessionLock) {
            if (!hasBridgeForRemote(remoteSteamId)) {
                idleSessionDeadlines.put(remoteSteamId, newIdleSessionDeadline());
            }
        }
    }

    void unregister(SteamConnectionBridge bridge) {
        closeUdpBridge(bridge);
        purgeOutbound(bridge);
        boolean removed = false;
        boolean anotherBridgeExists = false;
        synchronized (peerSessionLock) {
            if (bridgeRegistry.remove(
                    new SteamBridgeRegistry.Key(bridge.remoteSteamId(), bridge.connectionId()),
                    bridge
            )) {
                removed = true;
                anotherBridgeExists = bridge.isHostSide()
                        ? hasBridgeForRemote(bridge.remoteSteamId())
                        : hasClientBridgeForRemote(bridge.remoteSteamId());
                if (!hasBridgeForRemote(bridge.remoteSteamId())) {
                    idleSessionDeadlines.put(bridge.remoteSteamId(), newIdleSessionDeadline());
                }
            }
        }
        if (removed && !bridge.isHostSide()) {
            boolean finalAnotherBridgeExists = anotherBridgeExists;
            submitSteamTaskIfRunning(() -> {
                SteamLobbyManager current = lobbyManager;
                if (current != null) {
                    current.clientBridgeClosed(bridge.remoteSteamId(), finalAnotherBridgeExists);
                }
                return null;
            });
        }
        bridge.releaseActivity();
    }

    public void shutdown() {
        WorkerGeneration target;
        synchronized (lifecycleLock) {
            if (permanentlyShutdown) {
                return;
            }
            permanentlyShutdown = true;
            target = generation;
            if (target != null) {
                target.stopRequested.set(true);
                status = Status.STOPPING;
            }
        }

        SteamClientBridge.cancelPending();
        hostRegistration = null;
        for (SteamConnectionBridge bridge : bridgeRegistry.snapshot()) {
            bridge.close(false);
        }
        clearOutbound();
        pendingPeers.clear();
        idleSessionDeadlines.clear();

        Thread worker = target == null ? null : target.worker;
        if (worker != null) {
            worker.interrupt();
            if (worker != Thread.currentThread()) {
                try {
                    worker.join(2000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        } else {
            status = Status.STOPPED;
        }
    }

    private WorkerGeneration ensureWorkerStarted() throws IOException {
        synchronized (lifecycleLock) {
            long deadline = System.currentTimeMillis() + START_TIMEOUT.toMillis();
            while (generation != null && generation.stopRequested.get()) {
                if (permanentlyShutdown) {
                    throw new IOException("Steam runtime has been shut down");
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new IOException("Timed out while waiting for the previous Steam runtime to stop");
                }
                try {
                    lifecycleLock.wait(Math.min(remaining, 250));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for Steam to restart", exception);
                }
            }
            if (permanentlyShutdown) {
                throw new IOException("Steam runtime has been shut down");
            }
            if (generation != null) {
                return generation;
            }

            failureCause = null;
            localSteamId = 0;
            status = Status.STARTING;
            WorkerGeneration created = new WorkerGeneration();
            Thread worker = new Thread(() -> runWorker(created), "e4steam-steam-runtime");
            worker.setDaemon(true);
            created.worker = worker;
            generation = created;
            workerThread = worker;
            worker.start();
            return created;
        }
    }

    private void runWorker(WorkerGeneration currentGeneration) {
        Throwable workerFailure = null;
        try {
            initializeSteam();
            synchronized (lifecycleLock) {
                if (generation != currentGeneration || currentGeneration.stopRequested.get()) {
                    throw new IOException("Steam runtime was stopped during initialization");
                }
                status = Status.RUNNING;
            }
            currentGeneration.ready.complete(null);
            E4steamClient.LOGGER.info(
                    "Steam Networking Messages initialized as {} using App ID {}",
                    steamId(),
                    APP_ID
            );

            ByteBuffer sendBuffer = ByteBuffer.allocateDirect(SteamProtocol.MAX_PACKET_SIZE);
            ByteBuffer receiveBuffer = ByteBuffer.allocateDirect(SteamProtocol.MAX_ACCEPTED_STEAM_PACKET_SIZE);

            while (!currentGeneration.stopRequested.get()) {
                if (!steamLifecycle.isRunning()) {
                    throw new IOException("Steam disconnected while e4steam was active");
                }
                steamLifecycle.runCallbacks();
                drainSteamTasks();
                acceptKnownPeerSessions(System.currentTimeMillis());
                drainOutbound(sendBuffer);
                receivePackets(receiveBuffer);
                cleanupPeerSessions();
                SteamLobbyManager currentSocial = lobbyManager;
                if (currentSocial != null) {
                    currentSocial.cleanup(System.currentTimeMillis());
                }
                if (shouldStopForIdle(currentGeneration, System.currentTimeMillis())) {
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    // Wake-ups are used for queued Steam tasks and lifecycle changes.
                }
            }
        } catch (Throwable throwable) {
            workerFailure = throwable;
            failureCause = throwable;
            synchronized (lifecycleLock) {
                status = Status.FAILED;
            }
            currentGeneration.ready.completeExceptionally(throwable);
            E4steamClient.LOGGER.error("Steam runtime failed", throwable);
        } finally {
            HostRegistration failedHost;
            synchronized (lifecycleLock) {
                failedHost = hostRegistration;
                hostRegistration = null;
            }
            ArrayList<SteamConnectionBridge> failedBridges = new ArrayList<>(bridgeRegistry.snapshot());
            for (SteamConnectionBridge bridge : failedBridges) {
                bridge.close(false);
            }
            // A bridge that had already queued RESET is closed but still
            // registered. Explicit unregistration is required here so its
            // capacity permit and optional Activity survive no restart.
            for (SteamConnectionBridge bridge : failedBridges) {
                unregister(bridge);
            }
            bridgeRegistry.clear();
            clearOutbound();
            pendingPeers.clear();
            idleSessionDeadlines.clear();

            if (workerFailure != null && failedHost != null) {
                failedHost.owner().runtimeFailed(
                        workerFailure
                );
            }

            SteamLobbyManager currentSocial = lobbyManager;
            lobbyManager = null;
            if (currentSocial != null) {
                try {
                    currentSocial.close();
                } catch (Throwable ignored) {
                }
            }

            SteamNetworkingMessagesTransport currentTransport = transport;
            transport = null;
            if (currentTransport != null) {
                try {
                    currentTransport.close();
                } catch (Throwable ignored) {
                }
            }
            SteamUser currentUser = user;
            user = null;
            if (currentUser != null) {
                try {
                    currentUser.dispose();
                } catch (Throwable ignored) {
                }
            }
            SteamUtils currentUtils = utils;
            utils = null;
            if (currentUtils != null) {
                try {
                    currentUtils.dispose();
                } catch (Throwable ignored) {
                }
            }
            try {
                steamLifecycle.close();
            } catch (Throwable ignored) {
            }
            failPendingSteamTasks(workerFailure == null
                    ? new IOException("Steam runtime stopped")
                    : workerFailure);
            localSteamId = 0;
            synchronized (lifecycleLock) {
                if (generation == currentGeneration) {
                    generation = null;
                    workerThread = null;
                }
                if (workerFailure == null) {
                    status = Status.STOPPED;
                }
                lifecycleLock.notifyAll();
            }
        }
    }

    private boolean shouldStopForIdle(WorkerGeneration currentGeneration, long nowMillis) {
        synchronized (lifecycleLock) {
            if (permanentlyShutdown || generation != currentGeneration) {
                currentGeneration.stopRequested.set(true);
                status = Status.STOPPING;
                return true;
            }

            SteamLobbyManager currentSocial = lobbyManager;
            boolean keepAlive = activityCount > 0
                    || hostRegistration != null
                    || !bridgeRegistry.isEmpty()
                    || !outbound.isEmpty()
                    || !idleSessionDeadlines.isEmpty()
                    || !steamTasks.isEmpty()
                    || (currentSocial != null && currentSocial.keepsRuntimeAlive());
            if (keepAlive) {
                currentGeneration.idleSinceMillis = 0;
                return false;
            }
            if (currentGeneration.idleSinceMillis == 0) {
                currentGeneration.idleSinceMillis = nowMillis;
                return false;
            }
            if (nowMillis - currentGeneration.idleSinceMillis < RUNTIME_IDLE_SHUTDOWN_MILLIS) {
                return false;
            }

            status = Status.STOPPING;
            currentGeneration.stopRequested.set(true);
            return true;
        }
    }

    private <T> CompletableFuture<T> submitSteamTask(Callable<T> action) throws IOException {
        SteamTask<T> task = new SteamTask<>(action);
        synchronized (lifecycleLock) {
            WorkerGeneration current = generation;
            if (current == null
                    || current.stopRequested.get()
                    || status != Status.RUNNING
                    || permanentlyShutdown) {
                throw new IOException("Steam runtime is not available for this operation");
            }
            steamTasks.add(task);
            current.idleSinceMillis = 0;
            current.worker.interrupt();
        }
        return task.result;
    }

    private <T> CompletableFuture<T> submitSteamTaskIfRunning(Callable<T> action) {
        try {
            return submitSteamTask(action);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void drainSteamTasks() {
        for (int handled = 0; handled < 256; handled++) {
            SteamTask<?> task = steamTasks.poll();
            if (task == null) {
                return;
            }
            task.run();
        }
    }

    private void failPendingSteamTasks(Throwable cause) {
        SteamTask<?> task;
        while ((task = steamTasks.poll()) != null) {
            task.fail(cause);
        }
    }

    private static <T> T waitForSteamTask(
            CompletableFuture<T> task,
            Duration timeout,
            String operation
    ) throws IOException {
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new IOException("Timed out while " + operation, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while " + operation, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Steam failed while " + operation + ": " + cause.getMessage(), cause);
        }
    }

    private void releaseActivity() {
        synchronized (lifecycleLock) {
            if (activityCount > 0) {
                activityCount--;
            }
            WorkerGeneration current = generation;
            if (current != null) {
                current.worker.interrupt();
            }
        }
    }

    boolean isOverlayEnabledOnWorker() {
        SteamUtils current = utils;
        return current != null && current.isOverlayEnabled();
    }

    private boolean isWorkerStopping() {
        WorkerGeneration current = generation;
        return permanentlyShutdown || current == null || current.stopRequested.get();
    }

    private void initializeSteam() throws Exception {
        ensureAppIdFile();

        steamLifecycle.start();

        SteamUtils createdUtils = new SteamUtils(new SteamUtilsCallback() {
        });
        int initializedAppId = createdUtils.getAppID();
        if (initializedAppId != APP_ID) {
            createdUtils.dispose();
            throw new IOException(
                    "Steam initialized the Minecraft process with App ID " + initializedAppId
                            + " instead of the required App ID " + APP_ID
            );
        }
        utils = createdUtils;

        SteamUser createdUser = new SteamUser(new SteamUserCallback() {
        });
        SteamID id = createdUser.getSteamID();
        if (id == null || !id.isValid()) {
            createdUser.dispose();
            throw new IOException("Steam returned an invalid user ID");
        }

        localSteamId = SteamNativeHandle.getNativeHandle(id);
        user = createdUser;
        transport = SteamNetworkingMessagesTransport.open(
                steamLifecycle.steamApiPath(),
                new SteamNetworkingMessagesTransport.SessionListener() {
                    @Override
                    public void onSessionRequest(long remoteId) {
                        E4steamClient.LOGGER.debug(
                                "Steam Networking Messages session requested by {}",
                                Long.toUnsignedString(remoteId)
                        );
                        SteamNetworkingMessagesTransport current = transport;
                        if (current == null) {
                            return;
                        }
                        synchronized (peerSessionLock) {
                            SteamLobbyManager currentSocial = lobbyManager;
                            if (!hasBridgeForRemote(remoteId)
                                    && (currentSocial == null || !currentSocial.mayAcceptPeer(remoteId))) {
                                current.closePeer(remoteId);
                                return;
                            }
                            if (!hasBridgeForRemote(remoteId) && pendingPeers.size() >= MAX_PENDING_PEERS) {
                                current.closePeer(remoteId);
                                return;
                            }
                            if (!current.accept(remoteId)) {
                                current.closePeer(remoteId);
                                return;
                            }
                            if (!hasBridgeForRemote(remoteId)) {
                                pendingPeers.put(
                                        remoteId,
                                        System.currentTimeMillis() + PENDING_PEER_TIMEOUT_MILLIS
                                );
                            }
                        }
                    }

                    @Override
                    public void onSessionFailed(long remoteId, int endReason, String detail) {
                        if (detail.isBlank()) {
                            E4steamClient.LOGGER.warn(
                                    "Steam Networking Messages session with {} failed (reason {})",
                                    Long.toUnsignedString(remoteId),
                                    endReason
                            );
                        } else {
                            E4steamClient.LOGGER.warn(
                                    "Steam Networking Messages session with {} failed (reason {}): {}",
                                    Long.toUnsignedString(remoteId),
                                    endReason,
                                    detail
                            );
                        }
                        ArrayList<SteamConnectionBridge> failedBridges;
                        synchronized (peerSessionLock) {
                            pendingPeers.remove(remoteId);
                            idleSessionDeadlines.remove(remoteId);
                            failedBridges = new ArrayList<>(bridgeRegistry.snapshot().stream()
                                    .filter(bridge -> bridge.remoteSteamId() == remoteId)
                                    .toList());
                        }
                        SteamNetworkingMessagesTransport current = transport;
                        if (current != null) {
                            current.closePeer(remoteId);
                        }
                        for (SteamConnectionBridge bridge : failedBridges) {
                            bridge.close(false);
                        }
                    }
                }
        );
        lobbyManager = new SteamLobbyManager(this);
    }

    private void ensureAppIdFile() throws IOException {
        Path appIdFile = Path.of(System.getProperty("user.dir"), "steam_appid.txt").toAbsolutePath().normalize();
        if (Files.exists(appIdFile)) {
            String value = Files.readString(appIdFile, StandardCharsets.US_ASCII).trim();
            if (!Integer.toString(APP_ID).equals(value)) {
                throw new IOException(
                        "Refusing to overwrite " + appIdFile + "; expected App ID 480 but found '" + value + "'"
                );
            }
            return;
        }

        Files.writeString(
                appIdFile,
                Integer.toString(APP_ID) + System.lineSeparator(),
                StandardCharsets.US_ASCII,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        E4steamClient.LOGGER.info("Created {} for Steam App ID {}", appIdFile, APP_ID);
    }

    private void verifyRunning() throws IOException {
        if (status != Status.RUNNING || transport == null || isWorkerStopping()) {
            throw new IOException("Steam runtime is not running (status: " + status + ")");
        }
    }

    private boolean enqueueData(SteamConnectionBridge bridge, byte[] packet) {
        if (status != Status.RUNNING || isWorkerStopping() || bridge.isClosed()) {
            return false;
        }
        return outbound.offerData(
                bridge.remoteSteamId(),
                bridge.connectionId(),
                packet,
                bridge
        );
    }

    private boolean enqueueControl(
            long remoteSteamId,
            int connectionId,
            byte[] packet,
            SteamOutboundQueue.Kind kind,
            SteamConnectionBridge bridge
    ) {
        if (status != Status.RUNNING || isWorkerStopping()) {
            return false;
        }
        if (bridge != null && kind != SteamOutboundQueue.Kind.RESET && bridge.isClosed()) {
            return false;
        }
        return outbound.offerControl(remoteSteamId, connectionId, packet, kind, bridge);
    }

    private void drainOutbound(ByteBuffer buffer) throws Exception {
        SteamNetworkingMessagesTransport current = Objects.requireNonNull(transport);
        for (int sent = 0; sent < MAX_PACKETS_PER_TICK; sent++) {
            long now = System.currentTimeMillis();
            SteamOutboundQueue.Packet<SteamConnectionBridge> packet = retryOutboundPacket;
            if (packet != null && now < retryOutboundNotBeforeMillis) {
                return;
            }
            if (packet == null) {
                packet = outbound.poll();
            }
            if (packet == null) {
                return;
            }

            SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(
                    packet.remoteSteamId(),
                    packet.connectionId()
            );
            SteamConnectionBridge currentBridge = bridgeRegistry.get(key);
            if (!isPacketCurrent(packet, currentBridge)) {
                clearRetriedPacket(packet);
                continue;
            }

            buffer.clear();
            buffer.put(packet.payload()).flip();
            int result = current.sendResult(
                    packet.remoteSteamId(),
                    buffer,
                    packet.kind() == SteamOutboundQueue.Kind.DATAGRAM,
                    CHANNEL
            );
            boolean accepted = result == 1;
            SteamConnectionBridge packetBridge = packet.bridge();
            if (accepted) {
                clearRetriedPacket(packet);
            }
            if (packet.kind() == SteamOutboundQueue.Kind.RESET && packetBridge != null) {
                packetBridge.markResetSubmitted();
            } else if (accepted && packet.kind() == SteamOutboundQueue.Kind.FIN && packetBridge != null) {
                packetBridge.markFinSubmitted();
            } else if (!accepted && packetBridge != null && packet.kind() != SteamOutboundQueue.Kind.DATAGRAM) {
                SteamResult failure = SteamResult.byValue(result);
                if (isRetryableSendFailure(failure)) {
                    if (retryOutboundPacket != packet) {
                        retryOutboundPacket = packet;
                        retryOutboundDeadlineMillis = now + OUTBOUND_SEND_RETRY_TIMEOUT_MILLIS;
                        E4steamClient.LOGGER.debug(
                                "Steam applied outbound backpressure for {}; preserving and retrying {}",
                                Long.toUnsignedString(packet.remoteSteamId()),
                                packet.kind()
                        );
                    }
                    if (now < retryOutboundDeadlineMillis) {
                        retryOutboundNotBeforeMillis = now + OUTBOUND_SEND_RETRY_DELAY_MILLIS;
                        return;
                    }
                    clearRetriedPacket(packet);
                }
                E4steamClient.LOGGER.warn(
                        "Steam Networking Messages send to {} failed for {}: {} ({})",
                        Long.toUnsignedString(packet.remoteSteamId()),
                        packet.kind(),
                        failure,
                        result
                );
                packetBridge.close(false);
            }
        }
    }

    private void acceptKnownPeerSessions(long now) {
        if (now < nextKnownPeerAcceptAtMillis) {
            return;
        }
        nextKnownPeerAcceptAtMillis = now + KNOWN_PEER_ACCEPT_INTERVAL_MILLIS;
        SteamLobbyManager currentSocial = lobbyManager;
        SteamNetworkingMessagesTransport currentTransport = transport;
        if (currentSocial == null || currentTransport == null) {
            return;
        }
        currentSocial.forEachKnownSessionPeer(remoteSteamId -> {
            synchronized (peerSessionLock) {
                if (hasBridgeForRemote(remoteSteamId)
                        || pendingPeers.containsKey(remoteSteamId)
                        || pendingPeers.size() >= MAX_PENDING_PEERS) {
                    return;
                }
                if (currentTransport.accept(remoteSteamId)) {
                    pendingPeers.put(
                            remoteSteamId,
                            System.currentTimeMillis() + PENDING_PEER_TIMEOUT_MILLIS
                    );
                    E4steamClient.LOGGER.debug(
                            "Accepted Steam session for known lobby peer {}",
                            Long.toUnsignedString(remoteSteamId)
                    );
                }
            }
        });
    }

    private void clearOutbound() {
        outbound.clear();
        retryOutboundPacket = null;
        retryOutboundNotBeforeMillis = 0;
        retryOutboundDeadlineMillis = 0;
    }

    private void purgeOutbound(SteamConnectionBridge bridge) {
        outbound.purge(bridge);
        SteamOutboundQueue.Packet<SteamConnectionBridge> retry = retryOutboundPacket;
        if (retry != null && retry.bridge() == bridge) {
            clearRetriedPacket(retry);
        }
    }

    private void clearRetriedPacket(SteamOutboundQueue.Packet<SteamConnectionBridge> packet) {
        if (retryOutboundPacket == packet) {
            retryOutboundPacket = null;
            retryOutboundNotBeforeMillis = 0;
            retryOutboundDeadlineMillis = 0;
        }
    }

    static boolean isRetryableSendFailure(SteamResult result) {
        return result == SteamResult.LimitExceeded
                || result == SteamResult.Busy
                || result == SteamResult.NoConnection
                || result == SteamResult.ServiceUnavailable;
    }

    private boolean isPacketCurrent(
            SteamOutboundQueue.Packet<SteamConnectionBridge> packet,
            SteamConnectionBridge currentBridge
    ) {
        SteamConnectionBridge packetBridge = packet.bridge();
        if (packetBridge == null) {
            // A standalone RESET rejects an OPEN that never created a bridge.
            return packet.kind() == SteamOutboundQueue.Kind.RESET && currentBridge == null;
        }
        if (currentBridge != packetBridge) {
            return false;
        }
        return packet.kind() == SteamOutboundQueue.Kind.RESET || !packetBridge.isClosed();
    }

    private void receivePackets(ByteBuffer buffer) throws Exception {
        SteamNetworkingMessagesTransport current = Objects.requireNonNull(transport);
        for (int received = 0; received < MAX_PACKETS_PER_TICK; received++) {
            int size = current.availablePacketSize(CHANNEL);
            if (size == 0) {
                return;
            }

            if (size <= 0 || size > SteamProtocol.MAX_ACCEPTED_STEAM_PACKET_SIZE) {
                throw new IOException("Steam reported an invalid P2P packet size: " + size);
            }

            buffer.clear();
            SteamNetworkingMessagesTransport.Received packet = current.receive(buffer, CHANNEL);
            int read = packet.size();
            if (read <= 0) {
                continue;
            }
            if (read > SteamProtocol.MAX_PACKET_SIZE) {
                continue; // Foreign App ID 480 traffic; consume and ignore it.
            }
            if (packet.remoteSteamId() == 0) {
                continue; // Steam API peers must have an authenticated Steam identity.
            }

            buffer.position(0);
            buffer.limit(read);
            SteamProtocol.Frame frame = SteamProtocol.decode(buffer);
            if (frame == null) {
                continue; // App ID 480 is shared, so unrelated traffic is expected.
            }
            dispatchFrame(packet.remoteSteamId(), frame);
        }
    }

    private void dispatchFrame(long remoteSteamId, SteamProtocol.Frame frame) {
        SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(remoteSteamId, frame.connectionId());
        switch (frame.type()) {
            case SteamProtocol.OPEN -> handleOpen(remoteSteamId, key, frame.payload());
            case SteamProtocol.OPEN_ACK -> handleOpenAck(key, frame.payload());
            case SteamProtocol.DATA -> {
                SteamConnectionBridge bridge = bridgeRegistry.get(key);
                if (bridge != null) {
                    bridge.acceptSteamData(frame.payload());
                }
            }
            case SteamProtocol.FIN -> {
                SteamConnectionBridge bridge = bridgeRegistry.get(key);
                if (bridge != null) {
                    bridge.acceptRemoteFin();
                }
            }
            case SteamProtocol.RESET -> {
                SteamConnectionBridge bridge = bridgeRegistry.get(key);
                if (bridge != null) {
                    bridge.resetFromRemote();
                }
            }
            case SteamProtocol.DATAGRAM -> {
                SteamUdpBridge bridge = bridgeRegistry.getUdp(key);
                if (bridge != null) {
                    bridge.acceptSteamDatagram(frame.payload());
                }
            }
            default -> {
            }
        }
    }

    private void handleOpenAck(SteamBridgeRegistry.Key key, byte[] payload) {
        SteamConnectionBridge bridge = bridgeRegistry.get(key);
        if (bridge == null || bridge.isHostSide() || bridge.isClosed()) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte clientPortMode = buffer.get();
        int hostPort = Short.toUnsignedInt(buffer.getShort());
        try {
            startClientUdpBridge(
                    bridge,
                    VoiceChatUdpEndpoint.fromHandshake(hostPort, clientPortMode)
            );
        } catch (IllegalArgumentException exception) {
            bridge.close(true);
        }
    }

    private void handleOpen(long remoteSteamId, SteamBridgeRegistry.Key key, byte[] token) {
        HostRegistration registration = hostRegistration;
        SteamLobbyManager currentSocial = lobbyManager;
        boolean peerAllowed = registration != null
                && currentSocial != null
                && currentSocial.allows(registration.owner(), remoteSteamId);
        SteamInvitationAuthorizer.Decision authorization = SteamInvitationAuthorizer.authorize(
                registration == null ? null : registration.token(),
                token,
                peerAllowed
        );
        if (authorization != SteamInvitationAuthorizer.Decision.ALLOWED) {
            sendStandaloneReset(remoteSteamId, key.connectionId());
            return;
        }
        synchronized (peerSessionLock) {
            pendingPeers.remove(remoteSteamId);
            idleSessionDeadlines.remove(remoteSteamId);
        }
        if (bridgeRegistry.contains(key)) {
            return;
        }
        long activeHostConnections = bridgeRegistry.count(
                bridge -> bridge.isHostedBy(registration.owner()) && !bridge.isClosed()
        );
        if (activeHostConnections >= SteamLobbyManager.VANILLA_MAX_GUESTS) {
            sendStandaloneReset(remoteSteamId, key.connectionId());
            return;
        }
        if (System.currentTimeMillis() < nextLoopbackConnectAttemptAtMillis) {
            sendStandaloneReset(remoteSteamId, key.connectionId());
            return;
        }

        Socket socket = new Socket();
        boolean handedOff = false;
        try {
            socket.connect(
                    new InetSocketAddress("127.0.0.1", registration.localPort()),
                    LOOPBACK_CONNECT_TIMEOUT_MILLIS
            );
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            nextLoopbackConnectAttemptAtMillis = 0;

            if (hostRegistration != registration || status != Status.RUNNING || isWorkerStopping()) {
                sendStandaloneReset(remoteSteamId, key.connectionId());
                return;
            }

            SteamConnectionBridge bridge = new SteamConnectionBridge(
                    this,
                    remoteSteamId,
                    key.connectionId(),
                    socket,
                    registration.owner(),
                    null
            );
            SteamBridgeRegistry.Registration result = registerBridge(key, bridge);
            if (result != SteamBridgeRegistry.Registration.REGISTERED) {
                if (result != SteamBridgeRegistry.Registration.COLLISION) {
                    sendStandaloneReset(remoteSteamId, key.connectionId());
                }
                return;
            }
            handedOff = true;
            if (hostRegistration != registration) {
                bridge.close(true);
                return;
            }
            startHostUdpBridge(bridge, registration.udpEndpoint());
            if (!sendOpenAck(bridge, registration.udpEndpoint())) {
                bridge.close(true);
                return;
            }
            bridge.start();
            E4steamClient.LOGGER.info(
                    "Accepted Steam bridge from {}",
                    Long.toUnsignedString(remoteSteamId)
            );
        } catch (IOException exception) {
            nextLoopbackConnectAttemptAtMillis =
                    System.currentTimeMillis() + LOOPBACK_FAILURE_BACKOFF_MILLIS;
            sendStandaloneReset(remoteSteamId, key.connectionId());
            E4steamClient.LOGGER.warn("Could not connect a Steam guest to the local LAN server", exception);
        } finally {
            if (!handedOff) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void closeHostBridges(SteamSession owner) {
        for (SteamConnectionBridge bridge : bridgeRegistry.snapshot()) {
            if (bridge.isHostedBy(owner)) {
                bridge.close(true);
            }
        }
    }

    void closeRemoteBridges(long remoteSteamId) {
        for (SteamConnectionBridge bridge : bridgeRegistry.snapshot()) {
            if (bridge.remoteSteamId() == remoteSteamId) {
                bridge.close(true);
            }
        }
    }

    private SteamBridgeRegistry.Registration registerBridge(
            SteamBridgeRegistry.Key key,
            SteamConnectionBridge bridge
    ) {
        synchronized (peerSessionLock) {
            SteamBridgeRegistry.Registration result = bridgeRegistry.register(
                    key,
                    bridge,
                    () -> status == Status.RUNNING && !isWorkerStopping()
            );
            if (result != SteamBridgeRegistry.Registration.REGISTERED) {
                return result;
            }
            pendingPeers.remove(key.remoteSteamId());
            idleSessionDeadlines.remove(key.remoteSteamId());
            return SteamBridgeRegistry.Registration.REGISTERED;
        }
    }

    private boolean hasBridgeForRemote(long remoteSteamId) {
        return bridgeRegistry.any(bridge -> bridge.remoteSteamId() == remoteSteamId);
    }

    boolean hasClientBridgeForRemote(long remoteSteamId) {
        return bridgeRegistry.any(
                bridge -> !bridge.isHostSide() && bridge.remoteSteamId() == remoteSteamId
        );
    }

    private void closeSteamSessionIfIdle(long remoteSteamId) {
        synchronized (peerSessionLock) {
            if (!hasBridgeForRemote(remoteSteamId)) {
                pendingPeers.remove(remoteSteamId);
                idleSessionDeadlines.remove(remoteSteamId);
                closeSteamSession(remoteSteamId);
            }
        }
    }

    private void cleanupPeerSessions() {
        long now = System.currentTimeMillis();
        pendingPeers.forEach((remoteSteamId, deadline) -> {
            if (deadline <= now) {
                synchronized (peerSessionLock) {
                    if (!hasBridgeForRemote(remoteSteamId)
                            && pendingPeers.remove(remoteSteamId, deadline)) {
                        closeSteamSession(remoteSteamId);
                    }
                }
            }
        });
        idleSessionDeadlines.forEach((remoteSteamId, deadline) -> {
            if (deadline[0] <= now) {
                synchronized (peerSessionLock) {
                    if (idleSessionDeadlines.get(remoteSteamId) != deadline) {
                        return;
                    }
                    if (hasBridgeForRemote(remoteSteamId)) {
                        idleSessionDeadlines.remove(remoteSteamId);
                    } else if (now < deadline[1]
                            && hasQueuedSteamPackets(remoteSteamId)) {
                        idleSessionDeadlines.put(
                                remoteSteamId,
                                new long[]{now + IDLE_SESSION_RECHECK_MILLIS, deadline[1]}
                        );
                    } else {
                        idleSessionDeadlines.remove(remoteSteamId);
                        closeSteamSession(remoteSteamId);
                    }
                }
            }
        });
    }

    private long[] newIdleSessionDeadline() {
        long now = System.currentTimeMillis();
        return new long[]{
                now + IDLE_SESSION_CLOSE_DELAY_MILLIS,
                now + IDLE_SESSION_MAX_DRAIN_MILLIS
        };
    }

    private boolean hasQueuedSteamPackets(long remoteSteamId) {
        SteamNetworkingMessagesTransport current = transport;
        if (current == null) {
            return false;
        }
        return current.hasQueuedPackets(remoteSteamId);
    }

    private void closeSteamSession(long remoteSteamId) {
        SteamNetworkingMessagesTransport current = transport;
        if (current != null) {
            current.closePeer(remoteSteamId);
        }
    }

    private enum Status {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED,
        STOPPED
    }

    private record HostRegistration(
            SteamSession owner,
            int localPort,
            VoiceChatUdpEndpoint udpEndpoint,
            byte[] token,
            SteamAccessMode accessMode
    ) {
    }

    /** A restart-safe lease that keeps Spacewar/Steamworks active while needed. */
    public static final class Activity implements AutoCloseable {
        private final SteamRuntime runtime;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Activity(SteamRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                runtime.releaseActivity();
            }
        }
    }

    private static final class WorkerGeneration {
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private volatile Thread worker;
        private long idleSinceMillis;
    }

    private static final class SteamTask<T> {
        private final Callable<T> action;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private SteamTask(Callable<T> action) {
            this.action = action;
        }

        private void run() {
            try {
                result.complete(action.call());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        }

        private void fail(Throwable throwable) {
            result.completeExceptionally(throwable);
        }
    }
}
