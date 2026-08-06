package link.e4steam.steam

import link.e4steam.E4steamClient
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bridges local UDP datagrams to the Steam peer associated with a Minecraft TCP bridge. */
class SteamUdpBridge private constructor(
    private val runtime: SteamRuntime,
    private val owner: SteamConnectionBridge,
    private val socket: DatagramSocket,
    private val hostSide: Boolean
) {
    private val localClient = AtomicReference<SocketAddress>()
    private val closed = AtomicBoolean()

    @Volatile
    private var readerThread: Thread? = null

    fun owner(): SteamConnectionBridge = owner

    fun isClosed(): Boolean = closed.get()

    fun localPort(): Int = socket.localPort

    fun hasLocalClient(): Boolean = localClient.get() != null

    fun start() {
        if (closed.get() || readerThread != null) {
            return
        }
        val thread = Thread(
            { readLoop() },
            "e4steam-steam-udp-" + java.lang.Long.toUnsignedString(owner.remoteSteamId()) +
                "-" + Integer.toUnsignedString(owner.connectionId())
        )
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    fun acceptSteamDatagram(payload: ByteArray) {
        if (closed.get()) {
            return
        }
        try {
            val packet: DatagramPacket = if (hostSide) {
                DatagramPacket(payload, payload.size)
            } else {
                val destination = localClient.get()
                if (destination == null) {
                    return
                }
                DatagramPacket(payload, payload.size, destination)
            }
            socket.send(packet)
        } catch (exception: IOException) {
            if (!closed.get()) {
                E4steamClient.LOGGER.debug("Could not deliver a tunneled UDP datagram", exception)
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        socket.close()
        val thread = readerThread
        if (thread != null) {
            thread.interrupt()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
                if (!hostSide) {
                    val source = packet.address
                    if (source == null || !source.isLoopbackAddress) {
                        continue
                    }
                    localClient.set(packet.socketAddress)
                }
                if (packet.length > SteamProtocol.MAX_DATAGRAM_SIZE) {
                    E4steamClient.LOGGER.debug(
                        "Dropping oversized UDP datagram ({} bytes; maximum {})",
                        packet.length,
                        SteamProtocol.MAX_DATAGRAM_SIZE
                    )
                    continue
                }
                val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                runtime.sendDatagram(this, payload)
            } catch (exception: SocketException) {
                if (!closed.get()) {
                    E4steamClient.LOGGER.debug("UDP tunnel socket stopped", exception)
                }
                return
            } catch (exception: IOException) {
                if (!closed.get()) {
                    E4steamClient.LOGGER.debug("UDP tunnel reader stopped", exception)
                }
                return
            }
        }
    }

    companion object {
        private const val MAX_UDP_PACKET_SIZE = 65_507

        @JvmStatic
        @Throws(IOException::class)
        fun client(runtime: SteamRuntime, owner: SteamConnectionBridge, port: Int): SteamUdpBridge {
            val socket = DatagramSocket(null)
            var ready = false
            try {
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(loopback(), port))
                val bridge = SteamUdpBridge(runtime, owner, socket, false)
                ready = true
                return bridge
            } finally {
                if (!ready) {
                    socket.close()
                }
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun host(runtime: SteamRuntime, owner: SteamConnectionBridge, port: Int): SteamUdpBridge {
            val socket = DatagramSocket(null)
            var ready = false
            try {
                socket.bind(InetSocketAddress(loopback(), 0))
                socket.connect(InetSocketAddress(loopback(), port))
                val bridge = SteamUdpBridge(runtime, owner, socket, true)
                ready = true
                return bridge
            } finally {
                if (!ready) {
                    socket.close()
                }
            }
        }

        @Throws(IOException::class)
        private fun loopback(): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
