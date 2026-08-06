package link.e4steam.steam

import link.e4steam.E4steamClient
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bridges one ordinary local TCP connection to one logical Steam P2P stream. */
class SteamConnectionBridge(
    private val runtime: SteamRuntime,
    private val remoteSteamId: Long,
    private val connectionId: Int,
    private val socket: Socket,
    private val hostOwner: SteamSession?,
    activity: SteamRuntime.Activity?
) {
    // Eight MiB at the current 32 KiB protocol chunk size. This absorbs
    // registry/chunk bursts while remaining bounded for multiple players.
    private val inbound: BlockingQueue<InboundFrame> = ArrayBlockingQueue(MAX_QUEUED_INBOUND_CHUNKS + 1)
    private val inboundDataSlots = Semaphore(MAX_QUEUED_INBOUND_CHUNKS)
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val outboundFinQueued = AtomicBoolean()
    private val outboundFinSubmitted = AtomicBoolean()
    private val inboundFinQueued = AtomicBoolean()
    private val inboundFinished = AtomicBoolean()
    private val activity = AtomicReference(activity)

    @Volatile
    private var readerThread: Thread? = null
    @Volatile
    private var writerThread: Thread? = null

    fun remoteSteamId(): Long = remoteSteamId

    fun connectionId(): Int = connectionId

    fun localPort(): Int = socket.localPort

    fun isHostSide(): Boolean = hostOwner != null

    fun isHostedBy(owner: SteamSession): Boolean = hostOwner === owner

    fun isClosed(): Boolean = closed.get()

    fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) {
            return
        }

        writerThread = daemonThread({ writeLoop() }, "e4steam-steam-local-writer")
        readerThread = daemonThread({ readLoop() }, "e4steam-steam-local-reader")
        writerThread!!.start()
        readerThread!!.start()
    }

    fun acceptSteamData(payload: ByteArray) {
        if (closed.get()) {
            return
        }
        if (inboundFinQueued.get()) {
            closeForSlowOrInvalidPeer()
            return
        }
        if (!inboundDataSlots.tryAcquire()) {
            closeForSlowOrInvalidPeer()
            return
        }
        if (!inbound.offer(InboundData(payload))) {
            inboundDataSlots.release()
            closeForSlowOrInvalidPeer()
        }
    }

    private fun closeForSlowOrInvalidPeer() {
        if (!closed.get()) {
            E4steamClient.LOGGER.warn(
                "Closing Steam bridge {}:{} because its local TCP consumer is too slow or sent data after FIN",
                java.lang.Long.toUnsignedString(remoteSteamId),
                Integer.toUnsignedString(connectionId)
            )
            close(true)
        }
    }

    fun acceptRemoteFin() {
        if (closed.get() || !inboundFinQueued.compareAndSet(false, true)) {
            return
        }
        if (!inbound.offer(InboundFin.INSTANCE)) {
            close(true)
        }
    }

    fun resetFromRemote() {
        close(false)
    }

    fun markFinSubmitted() {
        if (outboundFinQueued.get()) {
            outboundFinSubmitted.set(true)
            closeIfFullyFinished()
        }
    }

    fun markResetSubmitted() {
        runtime.unregister(this)
    }

    /** Immediately aborts both directions. Graceful EOF is handled with FIN frames. */
    fun close(notifyRemote: Boolean) {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        try {
            runtime.closeUdpBridge(this)
            try {
                socket.close()
            } catch (ignored: IOException) {
            }
            inbound.clear()

            val reader = readerThread
            if (reader != null) {
                reader.interrupt()
            }
            val writer = writerThread
            if (writer != null) {
                writer.interrupt()
            }

            // Keep this exact bridge generation registered until its RESET reaches
            // Steam's send queue. That prevents a reused connection ID from being
            // affected by stale DATA/FIN/RESET frames belonging to this bridge.
            if (!notifyRemote || !runtime.sendReset(this)) {
                runtime.unregister(this)
            }
        } finally {
            releaseActivity()
        }
    }

    fun releaseActivity() {
        val activityToClose = activity.getAndSet(null) ?: return
        try {
            activityToClose.close()
        } catch (exception: Exception) {
            E4steamClient.LOGGER.warn("Could not release Steam bridge activity", exception)
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(SteamProtocol.DATA_CHUNK_SIZE)
        try {
            val input = socket.getInputStream()
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    if (outboundFinQueued.compareAndSet(false, true)) {
                        if (!runtime.sendFin(this)) {
                            close(true)
                        }
                    }
                    return
                }
                if (read == 0) {
                    continue
                }

                val payload = buffer.copyOf(read)
                sendDataWithBackpressure(payload)
            }
        } catch (exception: IOException) {
            if (!closed.get()) {
                E4steamClient.LOGGER.debug("Local TCP reader for a Steam bridge stopped", exception)
                close(true)
            }
        }
    }

    @Throws(IOException::class)
    private fun sendDataWithBackpressure(payload: ByteArray) {
        val deadline = System.currentTimeMillis() + OUTBOUND_BACKPRESSURE_TIMEOUT_MILLIS
        while (!closed.get()) {
            if (runtime.sendData(this, payload)) {
                return
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("Steam outbound queue remained full for 30 seconds")
            }
            try {
                Thread.sleep(OUTBOUND_BACKPRESSURE_RETRY_MILLIS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for Steam outbound capacity", exception)
            }
        }
        throw IOException("Steam bridge closed while waiting for outbound capacity")
    }

    private fun writeLoop() {
        try {
            val output = socket.getOutputStream()
            while (!closed.get()) {
                val frame = inbound.take()
                if (frame is InboundData) {
                    inboundDataSlots.release()
                    output.write(frame.payload)
                    output.flush()
                    continue
                }

                output.flush()
                socket.shutdownOutput()
                inboundFinished.set(true)
                closeIfFullyFinished()
                return
            }
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (exception: IOException) {
            if (!closed.get()) {
                E4steamClient.LOGGER.debug("Local TCP writer for a Steam bridge stopped", exception)
                close(true)
            }
        }
    }

    private fun closeIfFullyFinished() {
        if (outboundFinSubmitted.get() && inboundFinished.get()) {
            close(false)
        }
    }

    private fun daemonThread(action: () -> Unit, role: String): Thread {
        val thread = Thread(
            action,
            role + "-" + java.lang.Long.toUnsignedString(remoteSteamId) + "-" + Integer.toUnsignedString(connectionId)
        )
        thread.isDaemon = true
        return thread
    }

    private interface InboundFrame {
    }

    private class InboundData(val payload: ByteArray) : InboundFrame {
    }

    private enum class InboundFin : InboundFrame {
        INSTANCE
    }

    companion object {
        private const val MAX_QUEUED_INBOUND_CHUNKS = 256
        private const val OUTBOUND_BACKPRESSURE_TIMEOUT_MILLIS = 30_000L
        private const val OUTBOUND_BACKPRESSURE_RETRY_MILLIS = 10L
    }
}
