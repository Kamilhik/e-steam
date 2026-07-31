package link.e4steam.steam;

import link.e4steam.E4steamClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Creates the temporary loopback endpoint used by an unmodified Minecraft client connection. */
public final class SteamClientBridge {
    private static final int ACCEPT_TIMEOUT_MILLIS = 30_000;
    private static final Object PENDING_LOCK = new Object();
    private static final Set<PendingAccept> PENDING_ACCEPTS = ConcurrentHashMap.newKeySet();

    private SteamClientBridge() {
    }

    public static InetSocketAddress open(SteamAddress address) throws IOException {
        SteamRuntime runtime = SteamRuntime.get();
        PendingAccept pending;
        synchronized (PENDING_LOCK) {
            pending = new PendingAccept(runtime.acquireActivity());
            PENDING_ACCEPTS.add(pending);
        }

        boolean acceptThreadStarted = false;
        try {
            runtime.awaitReady();

            InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            ServerSocket listener = new ServerSocket();
            try {
                listener.setReuseAddress(false);
                listener.bind(new InetSocketAddress(loopback, 0), 1);
                listener.setSoTimeout(ACCEPT_TIMEOUT_MILLIS);
                pending.attachListener(listener);
            } catch (IOException | RuntimeException exception) {
                closeQuietly(listener);
                throw exception;
            }

            Thread acceptThread = new Thread(
                    () -> acceptMinecraftConnection(runtime, address, pending, listener),
                    "e4steam-steam-client-accept"
            );
            acceptThread.setDaemon(true);
            acceptThread.start();
            acceptThreadStarted = true;

            return new InetSocketAddress(loopback, listener.getLocalPort());
        } finally {
            if (!acceptThreadStarted) {
                PENDING_ACCEPTS.remove(pending);
                pending.cancel();
            }
        }
    }

    /** Cancels every loopback endpoint which is still waiting for Minecraft to connect. */
    public static void cancelPending() {
        PendingAccept[] pending;
        synchronized (PENDING_LOCK) {
            pending = PENDING_ACCEPTS.toArray(PendingAccept[]::new);
            PENDING_ACCEPTS.clear();
        }
        for (PendingAccept accept : pending) {
            accept.cancel();
        }
    }

    private static void acceptMinecraftConnection(
            SteamRuntime runtime,
            SteamAddress address,
            PendingAccept pending,
            ServerSocket listener
    ) {
        Socket socket = null;
        SteamConnectionBridge bridge = null;
        SteamRuntime.Activity activity = null;
        boolean handedOff = false;
        try (listener) {
            socket = listener.accept();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);

            activity = pending.takeActivityForHandoff();
            PENDING_ACCEPTS.remove(pending);
            if (activity == null) {
                throw new IOException("Steam connection was cancelled");
            }

            int connectionId = runtime.nextConnectionId(address.steamId());
            bridge = runtime.registerClientBridge(address.steamId(), connectionId, socket, activity);
            activity = null;
            if (!runtime.sendOpen(bridge, address.token())) {
                throw new IOException("Steam outbound queue is unavailable");
            }
            bridge.start();
            handedOff = true;
            E4steamClient.LOGGER.info(
                    "Opened a local Minecraft bridge to Steam user {}",
                    Long.toUnsignedString(address.steamId())
            );
        } catch (SocketTimeoutException exception) {
            E4steamClient.LOGGER.debug("Timed out waiting for Minecraft to use a resolved Steam address");
        } catch (IOException exception) {
            if (pending.isCancelled()) {
                E4steamClient.LOGGER.debug("Cancelled a pending Steam client bridge");
            } else {
                E4steamClient.LOGGER.warn("Steam client bridge failed", exception);
            }
        } finally {
            PENDING_ACCEPTS.remove(pending);
            pending.cancel();
            if (!handedOff) {
                if (bridge != null) {
                    bridge.close(false);
                } else if (socket != null) {
                    closeQuietly(socket);
                }
                closeActivity(activity);
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static void closeActivity(SteamRuntime.Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            activity.close();
        } catch (Exception exception) {
            E4steamClient.LOGGER.warn("Could not release Steam activity", exception);
        }
    }

    private static final class PendingAccept {
        private SteamRuntime.Activity activity;
        private ServerSocket listener;
        private boolean cancelled;

        private PendingAccept(SteamRuntime.Activity activity) {
            this.activity = activity;
        }

        synchronized void attachListener(ServerSocket listener) throws IOException {
            if (cancelled) {
                throw new IOException("Steam connection was cancelled");
            }
            this.listener = listener;
        }

        synchronized SteamRuntime.Activity takeActivityForHandoff() {
            if (cancelled) {
                return null;
            }
            SteamRuntime.Activity result = activity;
            activity = null;
            listener = null;
            return result;
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        void cancel() {
            SteamRuntime.Activity activityToClose;
            ServerSocket listenerToClose;
            synchronized (this) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                activityToClose = activity;
                activity = null;
                listenerToClose = listener;
                listener = null;
            }
            if (listenerToClose != null) {
                closeQuietly(listenerToClose);
            }
            closeActivity(activityToClose);
        }
    }
}
