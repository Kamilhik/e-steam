package link.e4steam.steam

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import link.e4steam.E4steamClient
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.HashMap
import java.util.Objects

/**
 * Packet-oriented transport backed by ISteamNetworkingMessages, the
 * connectionless facade over Steam Networking Sockets.
 */
class SteamNetworkingMessagesTransport(
        private val nativeAccess: NativeAccess,
        private val listener: SessionListener
) : AutoCloseable {
    private val identities: MutableMap<Long, Memory> = HashMap()
    private val realTimeStatus = Memory(REAL_TIME_STATUS_SIZE)
    private val requestCallback = SessionRequestCallback { request -> handleSessionRequest(request) }
    private val failedCallback = SessionFailedCallback { failure -> handleSessionFailed(failure) }
    private val debugOutputCallback = DebugOutputCallback { type, message -> handleDebugOutput(type, message) }

    private var pendingMessage: Pointer? = null
    private var closed = false

    init {
        Objects.requireNonNull(nativeAccess, "nativeAccess")
        Objects.requireNonNull(listener, "listener")
        try {
            nativeAccess.setCallbacks(requestCallback, failedCallback)
            nativeAccess.setDebugOutput(4, debugOutputCallback)
            nativeAccess.initializeRelayNetworkAccess()
        } catch (exception: RuntimeException) {
            cleanupInitialization()
            throw exception
        } catch (exception: Error) {
            cleanupInitialization()
            throw exception
        }
    }

    private fun cleanupInitialization() {
        try {
            nativeAccess.setCallbacks(null, null)
            nativeAccess.setDebugOutput(0, null)
        } catch (_: Throwable) {
        }
    }

    @Throws(IOException::class)
    fun send(remoteSteamId: Long, payload: ByteBuffer, unreliable: Boolean, channel: Int): Boolean {
        return sendResult(remoteSteamId, payload, unreliable, channel) == RESULT_OK
    }

    @Throws(IOException::class)
    fun sendResult(remoteSteamId: Long, payload: ByteBuffer, unreliable: Boolean, channel: Int): Int {
        ensureOpen()
        if (!payload.isDirect) {
            throw IOException("Steam Networking Messages requires a direct send buffer")
        }
        val size = payload.remaining()
        var data = Native.getDirectBufferPointer(payload)
        if (data == null) {
            throw IOException("Could not access the direct Steam send buffer")
        }
        data = data.share(payload.position().toLong())
        val flags = if (unreliable) SEND_UNRELIABLE_NO_DELAY else SEND_RELIABLE_NO_NAGLE
        return nativeAccess.send(identity(remoteSteamId), data, size, flags, channel)
    }

    @Throws(IOException::class)
    fun availablePacketSize(channel: Int): Int {
        ensureOpen()
        if (pendingMessage == null) {
            val messages = arrayOfNulls<Pointer>(1)
            val count = nativeAccess.receive(channel, messages, 1)
            if (count < 0 || count > 1) {
                throw IOException("Steam returned an invalid received-message count: $count")
            }
            if (count == 0) {
                return 0
            }
            if (messages[0] == null) {
                throw IOException("Steam returned a null received message")
            }
            pendingMessage = messages[0]
        }
        val size = pendingMessage!!.getInt(MESSAGE_SIZE_OFFSET)
        if (size == 0) {
            nativeAccess.releaseMessage(pendingMessage!!)
            pendingMessage = null
        }
        return size
    }

    @Throws(IOException::class)
    fun receive(target: ByteBuffer, channel: Int): Received {
        ensureOpen()
        val message = pendingMessage
        pendingMessage = null
        if (message == null) {
            throw IOException("No Steam Networking Messages packet is available on channel $channel")
        }

        try {
            val size = message.getInt(MESSAGE_SIZE_OFFSET)
            if (size < 0) {
                throw IOException("Steam returned a negative message size: $size")
            }
            if (size > target.remaining()) {
                throw BufferOverflowException()
            }
            val data = message.getPointer(MESSAGE_DATA_OFFSET)
            if (size > 0 && data == null) {
                throw IOException("Steam returned a null message payload")
            }
            if (size > 0) {
                target.put(data!!.getByteBuffer(0, size.toLong()))
            }
            return Received(readSteamId(message.share(MESSAGE_IDENTITY_OFFSET)), size)
        } finally {
            nativeAccess.releaseMessage(message)
        }
    }

    fun accept(remoteSteamId: Long): Boolean {
        ensureOpenUnchecked()
        return nativeAccess.accept(identity(remoteSteamId))
    }

    fun closePeer(remoteSteamId: Long) {
        if (closed) {
            return
        }
        val identity = identities.remove(remoteSteamId)
        nativeAccess.closeSession(identity ?: newIdentity(remoteSteamId))
    }

    fun hasQueuedPackets(remoteSteamId: Long): Boolean {
        if (closed) {
            return false
        }
        realTimeStatus.clear()
        val state = nativeAccess.getSessionConnectionInfo(identity(remoteSteamId), realTimeStatus)
        return state == CONNECTION_STATE_CONNECTING
                || state == CONNECTION_STATE_FINDING_ROUTE
                || realTimeStatus.getInt(STATUS_PENDING_UNRELIABLE_OFFSET) > 0
                || realTimeStatus.getInt(STATUS_PENDING_RELIABLE_OFFSET) > 0
                || realTimeStatus.getInt(STATUS_SENT_UNACKED_RELIABLE_OFFSET) > 0
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        nativeAccess.setCallbacks(null, null)
        nativeAccess.setDebugOutput(0, null)
        val message = pendingMessage
        pendingMessage = null
        if (message != null) {
            nativeAccess.releaseMessage(message)
        }
        for (identity in identities.values) {
            nativeAccess.closeSession(identity)
        }
        identities.clear()
    }

    private fun handleSessionRequest(request: Pointer?) {
        if (closed || request == null) {
            return
        }
        val remoteSteamId = readSteamId(request)
        if (remoteSteamId != 0L) {
            try {
                listener.onSessionRequest(remoteSteamId)
            } catch (throwable: Throwable) {
                E4steamClient.LOGGER.error("Steam session-request callback failed", throwable)
            }
        }
    }

    private fun handleSessionFailed(failure: Pointer?) {
        if (closed || failure == null) {
            return
        }
        val remoteSteamId = readSteamId(failure)
        if (remoteSteamId == 0L) {
            return
        }
        val endReason = failure.getInt(CONNECTION_INFO_END_REASON_OFFSET)
        val detail = readFixedString(failure, CONNECTION_INFO_END_DEBUG_OFFSET, CONNECTION_INFO_END_DEBUG_SIZE)
        try {
            listener.onSessionFailed(remoteSteamId, endReason, detail)
        } catch (throwable: Throwable) {
            E4steamClient.LOGGER.error("Steam session-failure callback failed", throwable)
        }
    }

    private fun handleDebugOutput(type: Int, message: String?) {
        if (message == null || message.isBlank()) {
            return
        }
        val clean = message.trim()
        if (type <= 2) {
            E4steamClient.LOGGER.warn("Steam Networking Sockets: {}", clean)
        } else {
            E4steamClient.LOGGER.debug("Steam Networking Sockets: {}", clean)
        }
    }

    private fun identity(remoteSteamId: Long): Memory {
        return identities.computeIfAbsent(remoteSteamId) { newIdentity(it) }
    }

    @Throws(IOException::class)
    private fun ensureOpen() {
        if (closed) {
            throw IOException("Steam Networking Messages transport is closed")
        }
    }

    private fun ensureOpenUnchecked() {
        if (closed) {
            throw IllegalStateException("Steam Networking Messages transport is closed")
        }
    }

    class Received(private val remoteSteamIdValue: Long, private val sizeValue: Int) {
        fun remoteSteamId(): Long = remoteSteamIdValue

        fun size(): Int = sizeValue
    }

    interface SessionListener {
        fun onSessionRequest(remoteSteamId: Long)

        fun onSessionFailed(remoteSteamId: Long, endReason: Int, detail: String)
    }

    fun interface SessionRequestCallback : Callback {
        fun invoke(request: Pointer)
    }

    fun interface SessionFailedCallback : Callback {
        fun invoke(failure: Pointer)
    }

    fun interface DebugOutputCallback : Callback {
        fun invoke(type: Int, message: String)
    }

    interface NativeAccess {
        fun send(identity: Pointer, data: Pointer, size: Int, flags: Int, channel: Int): Int

        fun receive(channel: Int, messages: Array<Pointer?>, maxMessages: Int): Int

        fun accept(identity: Pointer): Boolean

        fun closeSession(identity: Pointer)

        fun getSessionConnectionInfo(identity: Pointer, realTimeStatus: Pointer): Int

        fun initializeRelayNetworkAccess()

        fun releaseMessage(message: Pointer)

        fun setCallbacks(request: SessionRequestCallback?, failure: SessionFailedCallback?)

        fun setDebugOutput(detailLevel: Int, callback: DebugOutputCallback?)
    }

    companion object {
        private const val STEAM_IDENTITY_TYPE = 16
        private const val STEAM_IDENTITY_SIZE = 136L
        private const val STEAM_IDENTITY_VALUE_SIZE = java.lang.Long.BYTES

        private const val RESULT_OK = 1
        private const val SEND_NO_NAGLE = 1
        private const val SEND_NO_DELAY = 4
        private const val SEND_RELIABLE = 8
        private const val SEND_UNRELIABLE_NO_DELAY = SEND_NO_NAGLE or SEND_NO_DELAY
        private const val SEND_RELIABLE_NO_NAGLE = SEND_RELIABLE or SEND_NO_NAGLE

        private const val CONNECTION_STATE_CONNECTING = 1
        private const val CONNECTION_STATE_FINDING_ROUTE = 2

        private const val IDENTITY_TYPE_OFFSET = 0L
        private const val IDENTITY_SIZE_OFFSET = 4L
        private const val IDENTITY_VALUE_OFFSET = 8L

        private const val MESSAGE_DATA_OFFSET = 0L
        private const val MESSAGE_SIZE_OFFSET = 8L
        private const val MESSAGE_IDENTITY_OFFSET = 16L

        private const val STATUS_PENDING_UNRELIABLE_OFFSET = 36L
        private const val STATUS_PENDING_RELIABLE_OFFSET = 40L
        private const val STATUS_SENT_UNACKED_RELIABLE_OFFSET = 44L
        private const val REAL_TIME_STATUS_SIZE = 128L

        private const val CONNECTION_INFO_END_REASON_OFFSET = 176L
        private const val CONNECTION_INFO_END_DEBUG_OFFSET = 180L
        private const val CONNECTION_INFO_END_DEBUG_SIZE = 128

        @JvmStatic
        @Throws(IOException::class)
        fun open(steamApiLibrary: Path, listener: SessionListener): SteamNetworkingMessagesTransport {
            return SteamNetworkingMessagesTransport(JnaNativeAccess(steamApiLibrary), listener)
        }

        @JvmStatic
        fun newIdentity(remoteSteamId: Long): Memory {
            val identity = Memory(STEAM_IDENTITY_SIZE)
            identity.clear()
            identity.setInt(IDENTITY_TYPE_OFFSET, STEAM_IDENTITY_TYPE)
            identity.setInt(IDENTITY_SIZE_OFFSET, STEAM_IDENTITY_VALUE_SIZE)
            identity.setLong(IDENTITY_VALUE_OFFSET, remoteSteamId)
            return identity
        }

        @JvmStatic
        fun readSteamId(identity: Pointer?): Long {
            if (identity == null
                    || identity.getInt(IDENTITY_TYPE_OFFSET) != STEAM_IDENTITY_TYPE
                    || identity.getInt(IDENTITY_SIZE_OFFSET) != STEAM_IDENTITY_VALUE_SIZE) {
                return 0
            }
            return identity.getLong(IDENTITY_VALUE_OFFSET)
        }

        private fun readFixedString(source: Pointer, offset: Long, maximumSize: Int): String {
            val bytes = source.getByteArray(offset, maximumSize)
            var length = 0
            while (length < bytes.size && bytes[length] != 0.toByte()) {
                length++
            }
            return String(bytes, 0, length, StandardCharsets.UTF_8)
        }

        private class JnaNativeAccess(steamApiLibrary: Path) : NativeAccess {
            private val api: FlatApi
            private val messages: Pointer
            private val utils: Pointer

            init {
                Objects.requireNonNull(steamApiLibrary, "steamApiLibrary")
                val loadedApi = try {
                    Native.load(steamApiLibrary.toAbsolutePath().normalize().toString(), FlatApi::class.java)
                } catch (exception: UnsatisfiedLinkError) {
                    throw IOException("Could not bind Steam Networking Messages", exception)
                } catch (exception: RuntimeException) {
                    throw IOException("Could not bind Steam Networking Messages", exception)
                }
                val loadedMessages = loadedApi.SteamAPI_SteamNetworkingMessages_SteamAPI_v002()
                val loadedUtils = loadedApi.SteamAPI_SteamNetworkingUtils_SteamAPI_v004()
                if (loadedMessages == null || loadedUtils == null) {
                    throw IOException("Steam Networking Messages is unavailable after SteamAPI initialization")
                }
                api = loadedApi
                messages = loadedMessages
                utils = loadedUtils
            }

            override fun send(identity: Pointer, data: Pointer, size: Int, flags: Int, channel: Int): Int {
                return api.SteamAPI_ISteamNetworkingMessages_SendMessageToUser(
                        messages,
                        identity,
                        data,
                        size,
                        flags,
                        channel
                )
            }

            override fun receive(channel: Int, output: Array<Pointer?>, maxMessages: Int): Int {
                return api.SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel(
                        messages,
                        channel,
                        output,
                        maxMessages
                )
            }

            override fun accept(identity: Pointer): Boolean {
                return api.SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser(messages, identity) != 0.toByte()
            }

            override fun closeSession(identity: Pointer) {
                api.SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser(messages, identity)
            }

            override fun getSessionConnectionInfo(identity: Pointer, realTimeStatus: Pointer): Int {
                return api.SteamAPI_ISteamNetworkingMessages_GetSessionConnectionInfo(
                        messages,
                        identity,
                        null,
                        realTimeStatus
                )
            }

            override fun initializeRelayNetworkAccess() {
                api.SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(utils)
            }

            override fun releaseMessage(message: Pointer) {
                api.SteamAPI_SteamNetworkingMessage_t_Release(message)
            }

            override fun setCallbacks(request: SessionRequestCallback?, failure: SessionFailedCallback?) {
                api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionRequest(utils, request)
                api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionFailed(utils, failure)
            }

            override fun setDebugOutput(detailLevel: Int, callback: DebugOutputCallback?) {
                api.SteamAPI_ISteamNetworkingUtils_SetDebugOutputFunction(utils, detailLevel, callback)
            }
        }

        private interface FlatApi : Library {
            fun SteamAPI_SteamNetworkingMessages_SteamAPI_v002(): Pointer

            fun SteamAPI_SteamNetworkingUtils_SteamAPI_v004(): Pointer

            fun SteamAPI_ISteamNetworkingMessages_SendMessageToUser(
                    self: Pointer,
                    identity: Pointer,
                    data: Pointer,
                    size: Int,
                    flags: Int,
                    channel: Int
            ): Int

            fun SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel(
                    self: Pointer,
                    channel: Int,
                    messages: Array<Pointer?>,
                    maxMessages: Int
            ): Int

            fun SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser(self: Pointer, identity: Pointer): Byte

            fun SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser(self: Pointer, identity: Pointer): Byte

            fun SteamAPI_ISteamNetworkingMessages_GetSessionConnectionInfo(
                    self: Pointer,
                    identity: Pointer,
                    connectionInfo: Pointer?,
                    realTimeStatus: Pointer
            ): Int

            fun SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(self: Pointer)

            fun SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionRequest(
                    self: Pointer,
                    callback: SessionRequestCallback?
            )

            fun SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionFailed(
                    self: Pointer,
                    callback: SessionFailedCallback?
            )

            fun SteamAPI_ISteamNetworkingUtils_SetDebugOutputFunction(
                    self: Pointer,
                    detailLevel: Int,
                    callback: DebugOutputCallback?
            )

            fun SteamAPI_SteamNetworkingMessage_t_Release(message: Pointer)
        }
    }
}
