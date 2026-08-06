package link.e4steam.steam

import link.e4steam.E4steamClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/** Creates the temporary loopback endpoint used by an unmodified Minecraft client connection. */
class SteamClientBridge private constructor() {
    private class PendingAccept(private var activity: SteamRuntime.Activity) {
        private var listener: ServerSocket? = null
        private var cancelled = false

        @Synchronized
        @Throws(IOException::class)
        fun attachListener(listener: ServerSocket) {
            if (cancelled) {
                throw IOException("Steam connection was cancelled")
            }
            this.listener = listener
        }

        @Synchronized
        fun takeActivityForHandoff(): SteamRuntime.Activity? {
            if (cancelled) {
                return null
            }
            val result = activity
            activity = null
            listener = null
            return result
        }

        @Synchronized
        fun isCancelled(): Boolean = cancelled

        fun cancel() {
            val activityToClose: SteamRuntime.Activity?
            val listenerToClose: ServerSocket?
            synchronized(this) {
                if (cancelled) {
                    return
                }
                cancelled = true
                activityToClose = activity
                activity = null
                listenerToClose = listener
                listener = null
            }
            if (listenerToClose != null) {
                closeQuietly(listenerToClose)
            }
            closeActivity(activityToClose)
        }
    }

    companion object {
        private const val ACCEPT_TIMEOUT_MILLIS = 30_000
        private val PENDING_LOCK = Any()
        private val PENDING_ACCEPTS: MutableSet<PendingAccept> = ConcurrentHashMap.newKeySet()

        @JvmStatic
        @Throws(IOException::class)
        fun open(address: SteamAddress): InetSocketAddress {
            val runtime = SteamRuntime.get()
            val pending: PendingAccept
            synchronized(PENDING_LOCK) {
                pending = PendingAccept(runtime.acquireActivity())
                PENDING_ACCEPTS.add(pending)
            }

            var acceptThreadStarted = false
            try {
                runtime.awaitReady()

                val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                val listener = ServerSocket()
                try {
                    listener.reuseAddress = false
                    listener.bind(InetSocketAddress(loopback, 0), 1)
                    listener.soTimeout = ACCEPT_TIMEOUT_MILLIS
                    pending.attachListener(listener)
                } catch (exception: IOException) {
                    closeQuietly(listener)
                    throw exception
                } catch (exception: RuntimeException) {
                    closeQuietly(listener)
                    throw exception
                }

                val acceptThread = Thread(
                    { acceptMinecraftConnection(runtime, address, pending, listener) },
                    "e4steam-steam-client-accept"
                )
                acceptThread.isDaemon = true
                acceptThread.start()
                acceptThreadStarted = true

                return InetSocketAddress(loopback, listener.localPort)
            } finally {
                if (!acceptThreadStarted) {
                    PENDING_ACCEPTS.remove(pending)
                    pending.cancel()
                }
            }
        }

        /** Cancels every loopback endpoint which is still waiting for Minecraft to connect. */
        @JvmStatic
        fun cancelPending() {
            val pending: List<PendingAccept>
            synchronized(PENDING_LOCK) {
                pending = PENDING_ACCEPTS.toList()
                PENDING_ACCEPTS.clear()
            }
            for (accept in pending) {
                accept.cancel()
            }
        }

        private fun acceptMinecraftConnection(
            runtime: SteamRuntime,
            address: SteamAddress,
            pending: PendingAccept,
            listener: ServerSocket
        ) {
            var socket: Socket? = null
            var bridge: SteamConnectionBridge? = null
            var activity: SteamRuntime.Activity? = null
            var handedOff = false
            try {
                listener.use { it ->
                    socket = it.accept()
                    it.tcpNoDelay = true
                    it.keepAlive = true

                    activity = pending.takeActivityForHandoff()
                    PENDING_ACCEPTS.remove(pending)
                    if (activity == null) {
                        throw IOException("Steam connection was cancelled")
                    }

                    val connectionId = runtime.nextConnectionId(address.steamId())
                    bridge = runtime.registerClientBridge(address.steamId(), connectionId, socket!!, activity!!)
                    activity = null
                    if (!runtime.sendOpen(bridge!!, address.token())) {
                        throw IOException("Steam outbound queue is unavailable")
                    }
                    bridge.start()
                    handedOff = true
                    E4steamClient.LOGGER.info(
                        "Opened a local Minecraft bridge to Steam user {}",
                        Long.toUnsignedString(address.steamId())
                    )
                }
            } catch (exception: SocketTimeoutException) {
                E4steamClient.LOGGER.debug("Timed out waiting for Minecraft to use a resolved Steam address")
            } catch (exception: IOException) {
                if (pending.isCancelled()) {
                    E4steamClient.LOGGER.debug("Cancelled a pending Steam client bridge")
                } else {
                    E4steamClient.LOGGER.warn("Steam client bridge failed", exception)
                }
            } finally {
                PENDING_ACCEPTS.remove(pending)
                pending.cancel()
                if (!handedOff) {
                    if (bridge != null) {
                        bridge.close(false)
                    } else if (socket != null) {
                        closeQuietly(socket)
                    }
                    closeActivity(activity)
                }
            }
        }

        private fun closeQuietly(closeable: AutoCloseable) {
            try {
                closeable.close()
            } catch (exception: Exception) {
                // ignored
            }
        }

        private fun closeActivity(activity: SteamRuntime.Activity?) {
            if (activity == null) {
                return
            }
            try {
                activity.close()
            } catch (exception: Exception) {
                E4steamClient.LOGGER.warn("Could not release Steam activity", exception)
            }
        }
    }
}
