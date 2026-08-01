package link.e4steam.steam;

import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamNetworking;
import com.codedisaster.steamworks.SteamNetworkingCallback;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUserCallback;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;
import link.e4steam.Agnos;
import link.e4steam.E4steamClient;
import link.e4steam.SessionLimits;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
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
    // 1792 DATA + 64 OPEN + 64 standalone RESET + two terminal frames for
    // each of 64 active bridges equals the queue's 2048-packet capacity.
    private static final int MAX_OUTBOUND_PACKETS = 2048;
    private static final int MAX_OUTBOUND_DATA_PACKETS = 1792;
    private static final int MAX_OUTBOUND_OPEN_PACKETS = 64;
    private static final int MAX_OUTBOUND_STANDALONE_RESETS = 64;
    private static final int MAX_PACKETS_PER_TICK = 512;
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

    private static final SteamRuntime INSTANCE = new SteamRuntime();

    private final Object lifecycleLock = new Object();
    private final Object peerSessionLock = new Object();
    private final Object outboundQueueLock = new Object();
    private final ArrayBlockingQueue<OutboundPacket> outbound = new ArrayBlockingQueue<>(MAX_OUTBOUND_PACKETS);
    private final Semaphore outboundDataSlots = new Semaphore(MAX_OUTBOUND_DATA_PACKETS);
    private final Semaphore outboundOpenSlots = new Semaphore(MAX_OUTBOUND_OPEN_PACKETS);
    private final Semaphore outboundStandaloneResetSlots = new Semaphore(MAX_OUTBOUND_STANDALONE_RESETS);
    private final Semaphore activeBridgeSlots = new Semaphore(MAX_ACTIVE_CONNECTIONS);
    private final ConcurrentHashMap<BridgeKey, SteamConnectionBridge> bridges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> pendingPeers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, IdleSessionDeadline> idleSessionDeadlines = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SteamTask<?>> steamTasks = new ConcurrentLinkedQueue<>();

    private volatile Status status = Status.NEW;
    private volatile Throwable failureCause;
    private volatile long localSteamId;
    private volatile Thread workerThread;
    private volatile WorkerGeneration generation;
    private volatile SteamNetworking networking;
    private volatile SteamUser user;
    private volatile SteamUtils utils;
    private volatile SteamSocial social;
    private volatile HostRegistration hostRegistration;
    private volatile long nextLoopbackConnectAttemptAtMillis;
    private volatile boolean ownsSteamApi;
    private volatile boolean librariesLoaded;
    private boolean permanentlyShutdown;
    private int activityCount;

    private SteamRuntime() {
        Thread shutdownHook = new Thread(this::shutdown, "e4steam-steam-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static SteamRuntime get() {
        return INSTANCE;
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

    void startHosting(
            SteamSession owner,
            int localPort,
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

        HostRegistration replacement = new HostRegistration(owner, localPort, token.clone(), accessMode);
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
            SteamSocial current = social;
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
            SteamSocial current = social;
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
            SteamSocial current = social;
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
            SteamSocial current = social;
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
            SteamSocial current = social;
            if (current != null) {
                current.cancelGuestJoin();
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> beginGuestConnect(String endpoint) {
        return submitSteamTaskIfRunning(() -> {
            SteamSocial current = social;
            return current != null && current.beginGuestConnect(endpoint);
        });
    }

    public CompletableFuture<Boolean> claimGuestInvite(String endpoint) {
        return submitSteamTaskIfRunning(() -> {
            SteamSocial current = social;
            return current != null && current.claimGuestInvite(endpoint);
        });
    }

    int nextConnectionId(long remoteSteamId) {
        int connectionId;
        do {
            connectionId = ThreadLocalRandom.current().nextInt();
        } while (connectionId == 0 || bridges.containsKey(new BridgeKey(remoteSteamId, connectionId)));
        return connectionId;
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
        BridgeKey key = new BridgeKey(remoteSteamId, connectionId);
        BridgeRegistration result = registerBridge(key, bridge);
        if (result != BridgeRegistration.REGISTERED) {
            String reason = switch (result) {
                case CAPACITY -> "Too many active Steam bridges";
                case COLLISION -> "Steam connection identifier collision";
                case UNAVAILABLE -> "Steam runtime stopped while opening the bridge";
                default -> "Could not register the Steam bridge";
            };
            throw new IOException(reason);
        }
        submitSteamTaskIfRunning(() -> {
            SteamSocial current = social;
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
                PacketKind.OPEN,
                bridge
        );
    }

    boolean sendData(SteamConnectionBridge bridge, byte[] payload) {
        return enqueueData(
                bridge,
                SteamProtocol.encodeData(bridge.connectionId(), payload)
        );
    }

    boolean sendFin(SteamConnectionBridge bridge) {
        return enqueueControl(
                bridge.remoteSteamId(),
                bridge.connectionId(),
                SteamProtocol.encodeFin(bridge.connectionId()),
                PacketKind.FIN,
                bridge
        );
    }

    boolean sendReset(SteamConnectionBridge bridge) {
        return enqueueControl(
                bridge.remoteSteamId(),
                bridge.connectionId(),
                SteamProtocol.encodeReset(bridge.connectionId()),
                PacketKind.RESET,
                bridge
        );
    }

    private void sendStandaloneReset(long remoteSteamId, int connectionId) {
        enqueueControl(
                remoteSteamId,
                connectionId,
                SteamProtocol.encodeReset(connectionId),
                PacketKind.RESET,
                null
        );
        synchronized (peerSessionLock) {
            if (!hasBridgeForRemote(remoteSteamId)) {
                idleSessionDeadlines.put(remoteSteamId, newIdleSessionDeadline());
            }
        }
    }

    void unregister(SteamConnectionBridge bridge) {
        purgeOutbound(bridge);
        boolean removed = false;
        boolean anotherBridgeExists = false;
        synchronized (peerSessionLock) {
            if (bridges.remove(new BridgeKey(bridge.remoteSteamId(), bridge.connectionId()), bridge)) {
                removed = true;
                activeBridgeSlots.release();
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
                SteamSocial current = social;
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
        for (SteamConnectionBridge bridge : new ArrayList<>(bridges.values())) {
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
            E4steamClient.LOGGER.info("Steam P2P initialized as {} using App ID {}", steamId(), APP_ID);

            ByteBuffer sendBuffer = ByteBuffer.allocateDirect(SteamProtocol.MAX_PACKET_SIZE);
            ByteBuffer receiveBuffer = ByteBuffer.allocateDirect(SteamProtocol.MAX_ACCEPTED_STEAM_PACKET_SIZE);

            while (!currentGeneration.stopRequested.get()) {
                SteamAPI.runCallbacks();
                drainSteamTasks();
                drainOutbound(sendBuffer);
                receivePackets(receiveBuffer);
                cleanupPeerSessions();
                SteamSocial currentSocial = social;
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
            ArrayList<SteamConnectionBridge> failedBridges = new ArrayList<>(bridges.values());
            for (SteamConnectionBridge bridge : failedBridges) {
                bridge.close(false);
            }
            // A bridge that had already queued RESET is closed but still
            // registered. Explicit unregistration is required here so its
            // capacity permit and optional Activity survive no restart.
            for (SteamConnectionBridge bridge : failedBridges) {
                unregister(bridge);
            }
            bridges.clear();
            clearOutbound();
            pendingPeers.clear();
            idleSessionDeadlines.clear();

            if (workerFailure != null && failedHost != null) {
                failedHost.owner().runtimeFailed(
                        workerFailure
                );
            }

            SteamSocial currentSocial = social;
            social = null;
            if (currentSocial != null) {
                try {
                    currentSocial.close();
                } catch (Throwable ignored) {
                }
            }

            SteamNetworking currentNetworking = networking;
            networking = null;
            if (currentNetworking != null) {
                try {
                    currentNetworking.dispose();
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
            if (ownsSteamApi) {
                try {
                    SteamAPI.shutdown();
                } catch (Throwable ignored) {
                }
                ownsSteamApi = false;
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

            SteamSocial currentSocial = social;
            boolean keepAlive = activityCount > 0
                    || hostRegistration != null
                    || !bridges.isEmpty()
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

        if (!librariesLoaded) {
            SteamNativeLibraryLoader nativeLoader = new SteamNativeLibraryLoader();
            if (!SteamAPI.loadLibraries(nativeLoader)) {
                throw new IOException(
                        "Could not load Steam native libraries: " + nativeLoader.failureDescription(),
                        nativeLoader.failureCause()
                );
            }
            librariesLoaded = true;
        }
        if (!SteamAPI.init()) {
            throw new IOException("SteamAPI_Init failed. Start Steam and sign in before launching Minecraft");
        }
        ownsSteamApi = true;
        if (!SteamAPI.isSteamRunning(true)) {
            throw new IOException("Steam is not running or the current user is not signed in");
        }

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
        networking = new SteamNetworking(new SteamNetworkingCallback() {
            @Override
            public void onP2PSessionRequest(SteamID remote) {
                SteamNetworking current = networking;
                if (current == null) {
                    return;
                }
                long remoteId = SteamNativeHandle.getNativeHandle(remote);
                synchronized (peerSessionLock) {
                    SteamSocial currentSocial = social;
                    if (!hasBridgeForRemote(remoteId)
                            && (currentSocial == null || !currentSocial.mayAcceptPeer(remoteId))) {
                        current.closeP2PSessionWithUser(remote);
                        return;
                    }
                    if (!hasBridgeForRemote(remoteId) && pendingPeers.size() >= MAX_PENDING_PEERS) {
                        current.closeP2PSessionWithUser(remote);
                        return;
                    }
                    if (!current.acceptP2PSessionWithUser(remote)) {
                        current.closeP2PSessionWithUser(remote);
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
            public void onP2PSessionConnectFail(
                    SteamID remote,
                    SteamNetworking.P2PSessionError error
            ) {
                long remoteId = SteamNativeHandle.getNativeHandle(remote);
                E4steamClient.LOGGER.warn(
                        "Steam P2P connection to {} failed: {}",
                        Long.toUnsignedString(remoteId),
                        error
                );
                ArrayList<SteamConnectionBridge> failedBridges;
                synchronized (peerSessionLock) {
                    pendingPeers.remove(remoteId);
                    idleSessionDeadlines.remove(remoteId);
                    failedBridges = bridges.values().stream()
                            .filter(bridge -> bridge.remoteSteamId() == remoteId)
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                }
                for (SteamConnectionBridge bridge : failedBridges) {
                    bridge.close(false);
                }
            }
        });
        if (!networking.allowP2PPacketRelay(true)) {
            E4steamClient.LOGGER.warn("Steam did not confirm P2P relay fallback, continuing with direct P2P attempts");
        }
        social = new SteamSocial(this);
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
        if (status != Status.RUNNING || networking == null || isWorkerStopping()) {
            throw new IOException("Steam runtime is not running (status: " + status + ")");
        }
    }

    private boolean enqueueData(SteamConnectionBridge bridge, byte[] packet) {
        synchronized (outboundQueueLock) {
            if (status != Status.RUNNING || isWorkerStopping() || bridge.isClosed()) {
                return false;
            }
            if (!outboundDataSlots.tryAcquire()) {
                return false;
            }
            if (!outbound.offer(new OutboundPacket(
                    bridge.remoteSteamId(),
                    bridge.connectionId(),
                    packet,
                    PacketKind.DATA,
                    bridge
            ))) {
                outboundDataSlots.release();
                return false;
            }
            return true;
        }
    }

    private boolean enqueueControl(
            long remoteSteamId,
            int connectionId,
            byte[] packet,
            PacketKind kind,
            SteamConnectionBridge bridge
    ) {
        synchronized (outboundQueueLock) {
            if (status != Status.RUNNING || isWorkerStopping()) {
                return false;
            }
            if (bridge != null && kind != PacketKind.RESET && bridge.isClosed()) {
                return false;
            }

            Semaphore categorySlots = controlCategorySlots(kind, bridge);
            if (categorySlots != null && !categorySlots.tryAcquire()) {
                return false;
            }
            if (!outbound.offer(new OutboundPacket(remoteSteamId, connectionId, packet, kind, bridge))) {
                if (categorySlots != null) {
                    categorySlots.release();
                }
                return false;
            }
            return true;
        }
    }

    private void drainOutbound(ByteBuffer buffer) throws Exception {
        SteamNetworking current = Objects.requireNonNull(networking);
        for (int sent = 0; sent < MAX_PACKETS_PER_TICK; sent++) {
            OutboundPacket packet = outbound.poll();
            if (packet == null) {
                return;
            }
            releasePacketSlot(packet);

            BridgeKey key = new BridgeKey(packet.remoteSteamId(), packet.connectionId());
            SteamConnectionBridge currentBridge = bridges.get(key);
            if (!isPacketCurrent(packet, currentBridge)) {
                continue;
            }

            buffer.clear();
            buffer.put(packet.payload()).flip();
            SteamID remote = SteamID.createFromNativeHandle(packet.remoteSteamId());
            boolean accepted = current.sendP2PPacket(
                    remote,
                    buffer,
                    SteamNetworking.P2PSend.Reliable,
                    CHANNEL
            );
            SteamConnectionBridge packetBridge = packet.bridge();
            if (packet.kind() == PacketKind.RESET && packetBridge != null) {
                packetBridge.markResetSubmitted();
            } else if (accepted && packet.kind() == PacketKind.FIN && packetBridge != null) {
                packetBridge.markFinSubmitted();
            } else if (!accepted && packetBridge != null) {
                packetBridge.close(false);
            }
        }
    }

    private void clearOutbound() {
        synchronized (outboundQueueLock) {
            OutboundPacket packet;
            while ((packet = outbound.poll()) != null) {
                releasePacketSlot(packet);
            }
        }
    }

    private void purgeOutbound(SteamConnectionBridge bridge) {
        synchronized (outboundQueueLock) {
            outbound.removeIf(packet -> {
                if (packet.bridge() != bridge) {
                    return false;
                }
                releasePacketSlot(packet);
                return true;
            });
        }
    }

    private Semaphore controlCategorySlots(PacketKind kind, SteamConnectionBridge bridge) {
        if (kind == PacketKind.OPEN) {
            return outboundOpenSlots;
        }
        if (kind == PacketKind.RESET && bridge == null) {
            return outboundStandaloneResetSlots;
        }
        return null;
    }

    private void releasePacketSlot(OutboundPacket packet) {
        if (packet.kind() == PacketKind.DATA) {
            outboundDataSlots.release();
            return;
        }
        Semaphore categorySlots = controlCategorySlots(packet.kind(), packet.bridge());
        if (categorySlots != null) {
            categorySlots.release();
        }
    }

    private boolean isPacketCurrent(OutboundPacket packet, SteamConnectionBridge currentBridge) {
        SteamConnectionBridge packetBridge = packet.bridge();
        if (packetBridge == null) {
            // A standalone RESET rejects an OPEN that never created a bridge.
            return packet.kind() == PacketKind.RESET && currentBridge == null;
        }
        if (currentBridge != packetBridge) {
            return false;
        }
        return packet.kind() == PacketKind.RESET || !packetBridge.isClosed();
    }

    private void receivePackets(ByteBuffer buffer) throws Exception {
        SteamNetworking current = Objects.requireNonNull(networking);
        int[] packetSize = new int[1];
        for (int received = 0; received < MAX_PACKETS_PER_TICK; received++) {
            packetSize[0] = 0;
            if (!current.isP2PPacketAvailable(CHANNEL, packetSize)) {
                return;
            }

            int size = packetSize[0];
            if (size <= 0 || size > SteamProtocol.MAX_ACCEPTED_STEAM_PACKET_SIZE) {
                throw new IOException("Steam reported an invalid P2P packet size: " + size);
            }

            buffer.clear();
            SteamID remote = new SteamID();
            int read = current.readP2PPacket(remote, buffer, CHANNEL);
            if (read <= 0) {
                continue;
            }
            if (read > SteamProtocol.MAX_PACKET_SIZE) {
                continue; // Foreign App ID 480 traffic; consume and ignore it.
            }

            buffer.position(0);
            buffer.limit(read);
            SteamProtocol.Frame frame = SteamProtocol.decode(buffer);
            if (frame == null) {
                continue; // App ID 480 is shared, so unrelated traffic is expected.
            }
            long remoteSteamId = SteamNativeHandle.getNativeHandle(remote);
            dispatchFrame(remoteSteamId, frame);
        }
    }

    private void dispatchFrame(long remoteSteamId, SteamProtocol.Frame frame) {
        BridgeKey key = new BridgeKey(remoteSteamId, frame.connectionId());
        switch (frame.type()) {
            case SteamProtocol.OPEN -> handleOpen(remoteSteamId, key, frame.payload());
            case SteamProtocol.DATA -> {
                SteamConnectionBridge bridge = bridges.get(key);
                if (bridge != null) {
                    bridge.acceptSteamData(frame.payload());
                }
            }
            case SteamProtocol.FIN -> {
                SteamConnectionBridge bridge = bridges.get(key);
                if (bridge != null) {
                    bridge.acceptRemoteFin();
                }
            }
            case SteamProtocol.RESET -> {
                SteamConnectionBridge bridge = bridges.get(key);
                if (bridge != null) {
                    bridge.resetFromRemote();
                }
            }
            default -> {
            }
        }
    }

    private void handleOpen(long remoteSteamId, BridgeKey key, byte[] token) {
        HostRegistration registration = hostRegistration;
        if (registration == null || !MessageDigest.isEqual(registration.token(), token)) {
            sendStandaloneReset(remoteSteamId, key.connectionId());
            return;
        }
        SteamSocial currentSocial = social;
        if (currentSocial == null || !currentSocial.allows(registration.owner(), remoteSteamId)) {
            sendStandaloneReset(remoteSteamId, key.connectionId());
            return;
        }
        synchronized (peerSessionLock) {
            pendingPeers.remove(remoteSteamId);
            idleSessionDeadlines.remove(remoteSteamId);
        }
        if (bridges.containsKey(key)) {
            return;
        }
        long activeHostConnections = bridges.values().stream()
                .filter(bridge -> bridge.isHostedBy(registration.owner()))
                .filter(bridge -> !bridge.isClosed())
                .count();
        if (activeHostConnections >= SessionLimits.maxGuests()) {
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
            BridgeRegistration result = registerBridge(key, bridge);
            if (result != BridgeRegistration.REGISTERED) {
                if (result != BridgeRegistration.COLLISION) {
                    sendStandaloneReset(remoteSteamId, key.connectionId());
                }
                return;
            }
            handedOff = true;
            if (hostRegistration != registration) {
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
        for (SteamConnectionBridge bridge : new ArrayList<>(bridges.values())) {
            if (bridge.isHostedBy(owner)) {
                bridge.close(true);
            }
        }
    }

    void closeRemoteBridges(long remoteSteamId) {
        for (SteamConnectionBridge bridge : new ArrayList<>(bridges.values())) {
            if (bridge.remoteSteamId() == remoteSteamId) {
                bridge.close(true);
            }
        }
    }

    private BridgeRegistration registerBridge(BridgeKey key, SteamConnectionBridge bridge) {
        synchronized (peerSessionLock) {
            if (status != Status.RUNNING || isWorkerStopping()) {
                return BridgeRegistration.UNAVAILABLE;
            }
            if (bridges.containsKey(key)) {
                return BridgeRegistration.COLLISION;
            }
            if (!activeBridgeSlots.tryAcquire()) {
                return BridgeRegistration.CAPACITY;
            }
            if (bridges.putIfAbsent(key, bridge) != null) {
                activeBridgeSlots.release();
                return BridgeRegistration.COLLISION;
            }
            pendingPeers.remove(key.remoteSteamId());
            idleSessionDeadlines.remove(key.remoteSteamId());
            return BridgeRegistration.REGISTERED;
        }
    }

    private boolean hasBridgeForRemote(long remoteSteamId) {
        return bridges.values().stream().anyMatch(bridge -> bridge.remoteSteamId() == remoteSteamId);
    }

    boolean hasClientBridgeForRemote(long remoteSteamId) {
        return bridges.values().stream()
                .anyMatch(bridge -> !bridge.isHostSide() && bridge.remoteSteamId() == remoteSteamId);
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
            if (deadline.nextCheckAtMillis() <= now) {
                synchronized (peerSessionLock) {
                    if (idleSessionDeadlines.get(remoteSteamId) != deadline) {
                        return;
                    }
                    if (hasBridgeForRemote(remoteSteamId)) {
                        idleSessionDeadlines.remove(remoteSteamId);
                    } else if (now < deadline.forceCloseAtMillis()
                            && hasQueuedSteamPackets(remoteSteamId)) {
                        idleSessionDeadlines.put(
                                remoteSteamId,
                                new IdleSessionDeadline(
                                        now + IDLE_SESSION_RECHECK_MILLIS,
                                        deadline.forceCloseAtMillis()
                                )
                        );
                    } else {
                        idleSessionDeadlines.remove(remoteSteamId);
                        closeSteamSession(remoteSteamId);
                    }
                }
            }
        });
    }

    private IdleSessionDeadline newIdleSessionDeadline() {
        long now = System.currentTimeMillis();
        return new IdleSessionDeadline(
                now + IDLE_SESSION_CLOSE_DELAY_MILLIS,
                now + IDLE_SESSION_MAX_DRAIN_MILLIS
        );
    }

    private boolean hasQueuedSteamPackets(long remoteSteamId) {
        SteamNetworking current = networking;
        if (current == null) {
            return false;
        }
        SteamNetworking.P2PSessionState state = new SteamNetworking.P2PSessionState();
        return current.getP2PSessionState(SteamID.createFromNativeHandle(remoteSteamId), state)
                && (state.isConnecting()
                || state.getPacketsQueuedForSend() > 0
                || state.getBytesQueuedForSend() > 0);
    }

    private void closeSteamSession(long remoteSteamId) {
        SteamNetworking current = networking;
        if (current != null) {
            current.closeP2PSessionWithUser(SteamID.createFromNativeHandle(remoteSteamId));
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

    private record BridgeKey(long remoteSteamId, int connectionId) {
    }

    private record OutboundPacket(
            long remoteSteamId,
            int connectionId,
            byte[] payload,
            PacketKind kind,
            SteamConnectionBridge bridge
    ) {
    }

    private enum PacketKind {
        OPEN,
        DATA,
        FIN,
        RESET
    }

    private enum BridgeRegistration {
        REGISTERED,
        COLLISION,
        CAPACITY,
        UNAVAILABLE
    }

    private record HostRegistration(
            SteamSession owner,
            int localPort,
            byte[] token,
            SteamAccessMode accessMode
    ) {
    }

    private record IdleSessionDeadline(long nextCheckAtMillis, long forceCloseAtMillis) {
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
