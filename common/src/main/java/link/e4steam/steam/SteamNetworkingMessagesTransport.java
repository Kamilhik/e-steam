package link.e4steam.steam;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import link.e4steam.E4steamClient;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Packet-oriented transport backed by ISteamNetworkingMessages, the
 * connectionless facade over Steam Networking Sockets.
 */
final class SteamNetworkingMessagesTransport implements AutoCloseable {
    private static final int STEAM_IDENTITY_TYPE = 16;
    private static final int STEAM_IDENTITY_SIZE = 136;
    private static final int STEAM_IDENTITY_VALUE_SIZE = Long.BYTES;

    private static final int RESULT_OK = 1;
    private static final int SEND_NO_NAGLE = 1;
    private static final int SEND_NO_DELAY = 4;
    private static final int SEND_RELIABLE = 8;
    private static final int SEND_UNRELIABLE_NO_DELAY = SEND_NO_NAGLE | SEND_NO_DELAY;
    private static final int SEND_RELIABLE_NO_NAGLE = SEND_RELIABLE | SEND_NO_NAGLE;

    private static final int CONNECTION_STATE_CONNECTING = 1;
    private static final int CONNECTION_STATE_FINDING_ROUTE = 2;

    private static final long IDENTITY_TYPE_OFFSET = 0;
    private static final long IDENTITY_SIZE_OFFSET = 4;
    private static final long IDENTITY_VALUE_OFFSET = 8;

    private static final long MESSAGE_DATA_OFFSET = 0;
    private static final long MESSAGE_SIZE_OFFSET = 8;
    private static final long MESSAGE_IDENTITY_OFFSET = 16;

    private static final long STATUS_PENDING_UNRELIABLE_OFFSET = 36;
    private static final long STATUS_PENDING_RELIABLE_OFFSET = 40;
    private static final long STATUS_SENT_UNACKED_RELIABLE_OFFSET = 44;
    private static final long REAL_TIME_STATUS_SIZE = 128;

    private static final long CONNECTION_INFO_END_REASON_OFFSET = 176;
    private static final long CONNECTION_INFO_END_DEBUG_OFFSET = 180;
    private static final int CONNECTION_INFO_END_DEBUG_SIZE = 128;

    record Received(long remoteSteamId, int size) {
    }

    interface SessionListener {
        void onSessionRequest(long remoteSteamId);

        void onSessionFailed(long remoteSteamId, int endReason, String detail);
    }

    interface SessionRequestCallback extends Callback {
        void invoke(Pointer request);
    }

    interface SessionFailedCallback extends Callback {
        void invoke(Pointer failure);
    }

    interface DebugOutputCallback extends Callback {
        void invoke(int type, String message);
    }

    interface NativeAccess {
        int send(Pointer identity, Pointer data, int size, int flags, int channel);

        int receive(int channel, Pointer[] messages, int maxMessages);

        boolean accept(Pointer identity);

        void closeSession(Pointer identity);

        int getSessionConnectionInfo(Pointer identity, Pointer realTimeStatus);

        void initializeRelayNetworkAccess();

        void releaseMessage(Pointer message);

        void setCallbacks(SessionRequestCallback request, SessionFailedCallback failure);

        void setDebugOutput(int detailLevel, DebugOutputCallback callback);
    }

    private final NativeAccess nativeAccess;
    private final SessionListener listener;
    private final Map<Long, Memory> identities = new HashMap<>();
    private final Memory realTimeStatus = new Memory(REAL_TIME_STATUS_SIZE);
    private final SessionRequestCallback requestCallback = this::handleSessionRequest;
    private final SessionFailedCallback failedCallback = this::handleSessionFailed;
    private final DebugOutputCallback debugOutputCallback = this::handleDebugOutput;

    private Pointer pendingMessage;
    private boolean closed;

    static SteamNetworkingMessagesTransport open(Path steamApiLibrary, SessionListener listener)
            throws IOException {
        return new SteamNetworkingMessagesTransport(new JnaNativeAccess(steamApiLibrary), listener);
    }

    SteamNetworkingMessagesTransport(NativeAccess nativeAccess, SessionListener listener) {
        this.nativeAccess = Objects.requireNonNull(nativeAccess, "nativeAccess");
        this.listener = Objects.requireNonNull(listener, "listener");
        try {
            nativeAccess.setCallbacks(requestCallback, failedCallback);
            nativeAccess.setDebugOutput(4, debugOutputCallback);
            nativeAccess.initializeRelayNetworkAccess();
        } catch (RuntimeException | Error exception) {
            try {
                nativeAccess.setCallbacks(null, null);
                nativeAccess.setDebugOutput(0, null);
            } catch (Throwable ignored) {
            }
            throw exception;
        }
    }

    boolean send(long remoteSteamId, ByteBuffer payload, boolean unreliable, int channel)
            throws IOException {
        return sendResult(remoteSteamId, payload, unreliable, channel) == RESULT_OK;
    }

    int sendResult(long remoteSteamId, ByteBuffer payload, boolean unreliable, int channel)
            throws IOException {
        ensureOpen();
        if (!payload.isDirect()) {
            throw new IOException("Steam Networking Messages requires a direct send buffer");
        }
        int size = payload.remaining();
        Pointer data = Native.getDirectBufferPointer(payload);
        if (data == null) {
            throw new IOException("Could not access the direct Steam send buffer");
        }
        data = data.share(payload.position());
        int flags = unreliable ? SEND_UNRELIABLE_NO_DELAY : SEND_RELIABLE_NO_NAGLE;
        return nativeAccess.send(identity(remoteSteamId), data, size, flags, channel);
    }

    int availablePacketSize(int channel) throws IOException {
        ensureOpen();
        if (pendingMessage == null) {
            Pointer[] messages = new Pointer[1];
            int count = nativeAccess.receive(channel, messages, 1);
            if (count < 0 || count > 1) {
                throw new IOException("Steam returned an invalid received-message count: " + count);
            }
            if (count == 0) {
                return 0;
            }
            if (messages[0] == null) {
                throw new IOException("Steam returned a null received message");
            }
            pendingMessage = messages[0];
        }
        int size = pendingMessage.getInt(MESSAGE_SIZE_OFFSET);
        if (size == 0) {
            nativeAccess.releaseMessage(pendingMessage);
            pendingMessage = null;
        }
        return size;
    }

    Received receive(ByteBuffer target, int channel) throws IOException {
        ensureOpen();
        Pointer message = pendingMessage;
        pendingMessage = null;
        if (message == null) {
            throw new IOException("No Steam Networking Messages packet is available on channel " + channel);
        }

        try {
            int size = message.getInt(MESSAGE_SIZE_OFFSET);
            if (size < 0) {
                throw new IOException("Steam returned a negative message size: " + size);
            }
            if (size > target.remaining()) {
                throw new BufferOverflowException();
            }
            Pointer data = message.getPointer(MESSAGE_DATA_OFFSET);
            if (size > 0 && data == null) {
                throw new IOException("Steam returned a null message payload");
            }
            if (size > 0) {
                target.put(data.getByteBuffer(0, size));
            }
            return new Received(readSteamId(message.share(MESSAGE_IDENTITY_OFFSET)), size);
        } finally {
            nativeAccess.releaseMessage(message);
        }
    }

    boolean accept(long remoteSteamId) {
        ensureOpenUnchecked();
        return nativeAccess.accept(identity(remoteSteamId));
    }

    void closePeer(long remoteSteamId) {
        if (closed) {
            return;
        }
        Memory identity = identities.remove(remoteSteamId);
        nativeAccess.closeSession(identity == null ? newIdentity(remoteSteamId) : identity);
    }

    boolean hasQueuedPackets(long remoteSteamId) {
        if (closed) {
            return false;
        }
        realTimeStatus.clear();
        int state = nativeAccess.getSessionConnectionInfo(identity(remoteSteamId), realTimeStatus);
        return state == CONNECTION_STATE_CONNECTING
                || state == CONNECTION_STATE_FINDING_ROUTE
                || realTimeStatus.getInt(STATUS_PENDING_UNRELIABLE_OFFSET) > 0
                || realTimeStatus.getInt(STATUS_PENDING_RELIABLE_OFFSET) > 0
                || realTimeStatus.getInt(STATUS_SENT_UNACKED_RELIABLE_OFFSET) > 0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        nativeAccess.setCallbacks(null, null);
        nativeAccess.setDebugOutput(0, null);
        Pointer message = pendingMessage;
        pendingMessage = null;
        if (message != null) {
            nativeAccess.releaseMessage(message);
        }
        for (Memory identity : identities.values()) {
            nativeAccess.closeSession(identity);
        }
        identities.clear();
    }

    private void handleSessionRequest(Pointer request) {
        if (closed || request == null) {
            return;
        }
        long remoteSteamId = readSteamId(request);
        if (remoteSteamId != 0) {
            try {
                listener.onSessionRequest(remoteSteamId);
            } catch (Throwable throwable) {
                E4steamClient.LOGGER.error("Steam session-request callback failed", throwable);
            }
        }
    }

    private void handleSessionFailed(Pointer failure) {
        if (closed || failure == null) {
            return;
        }
        long remoteSteamId = readSteamId(failure);
        if (remoteSteamId == 0) {
            return;
        }
        int endReason = failure.getInt(CONNECTION_INFO_END_REASON_OFFSET);
        String detail = readFixedString(
                failure,
                CONNECTION_INFO_END_DEBUG_OFFSET,
                CONNECTION_INFO_END_DEBUG_SIZE
        );
        try {
            listener.onSessionFailed(remoteSteamId, endReason, detail);
        } catch (Throwable throwable) {
            E4steamClient.LOGGER.error("Steam session-failure callback failed", throwable);
        }
    }

    private void handleDebugOutput(int type, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String clean = message.strip();
        if (type <= 2) {
            E4steamClient.LOGGER.warn("Steam Networking Sockets: {}", clean);
        } else {
            E4steamClient.LOGGER.debug("Steam Networking Sockets: {}", clean);
        }
    }

    private Memory identity(long remoteSteamId) {
        return identities.computeIfAbsent(remoteSteamId, SteamNetworkingMessagesTransport::newIdentity);
    }

    static Memory newIdentity(long remoteSteamId) {
        Memory identity = new Memory(STEAM_IDENTITY_SIZE);
        identity.clear();
        identity.setInt(IDENTITY_TYPE_OFFSET, STEAM_IDENTITY_TYPE);
        identity.setInt(IDENTITY_SIZE_OFFSET, STEAM_IDENTITY_VALUE_SIZE);
        identity.setLong(IDENTITY_VALUE_OFFSET, remoteSteamId);
        return identity;
    }

    static long readSteamId(Pointer identity) {
        if (identity == null
                || identity.getInt(IDENTITY_TYPE_OFFSET) != STEAM_IDENTITY_TYPE
                || identity.getInt(IDENTITY_SIZE_OFFSET) != STEAM_IDENTITY_VALUE_SIZE) {
            return 0;
        }
        return identity.getLong(IDENTITY_VALUE_OFFSET);
    }

    private static String readFixedString(Pointer source, long offset, int maximumSize) {
        byte[] bytes = source.getByteArray(offset, maximumSize);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Steam Networking Messages transport is closed");
        }
    }

    private void ensureOpenUnchecked() {
        if (closed) {
            throw new IllegalStateException("Steam Networking Messages transport is closed");
        }
    }

    private interface FlatApi extends Library {
        Pointer SteamAPI_SteamNetworkingMessages_SteamAPI_v002();

        Pointer SteamAPI_SteamNetworkingUtils_SteamAPI_v004();

        int SteamAPI_ISteamNetworkingMessages_SendMessageToUser(
                Pointer self,
                Pointer identity,
                Pointer data,
                int size,
                int flags,
                int channel
        );

        int SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel(
                Pointer self,
                int channel,
                Pointer[] messages,
                int maxMessages
        );

        byte SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser(Pointer self, Pointer identity);

        byte SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser(Pointer self, Pointer identity);

        int SteamAPI_ISteamNetworkingMessages_GetSessionConnectionInfo(
                Pointer self,
                Pointer identity,
                Pointer connectionInfo,
                Pointer realTimeStatus
        );

        void SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(Pointer self);

        void SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionRequest(
                Pointer self,
                SessionRequestCallback callback
        );

        void SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionFailed(
                Pointer self,
                SessionFailedCallback callback
        );

        void SteamAPI_ISteamNetworkingUtils_SetDebugOutputFunction(
                Pointer self,
                int detailLevel,
                DebugOutputCallback callback
        );

        void SteamAPI_SteamNetworkingMessage_t_Release(Pointer message);
    }

    private static final class JnaNativeAccess implements NativeAccess {
        private final FlatApi api;
        private final Pointer messages;
        private final Pointer utils;

        private JnaNativeAccess(Path steamApiLibrary) throws IOException {
            Objects.requireNonNull(steamApiLibrary, "steamApiLibrary");
            try {
                api = Native.load(steamApiLibrary.toAbsolutePath().normalize().toString(), FlatApi.class);
                messages = api.SteamAPI_SteamNetworkingMessages_SteamAPI_v002();
                utils = api.SteamAPI_SteamNetworkingUtils_SteamAPI_v004();
            } catch (UnsatisfiedLinkError | RuntimeException exception) {
                throw new IOException("Could not bind Steam Networking Messages", exception);
            }
            if (messages == null || utils == null) {
                throw new IOException("Steam Networking Messages is unavailable after SteamAPI initialization");
            }
        }

        @Override
        public int send(Pointer identity, Pointer data, int size, int flags, int channel) {
            return api.SteamAPI_ISteamNetworkingMessages_SendMessageToUser(
                    messages,
                    identity,
                    data,
                    size,
                    flags,
                    channel
            );
        }

        @Override
        public int receive(int channel, Pointer[] output, int maxMessages) {
            return api.SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel(
                    messages,
                    channel,
                    output,
                    maxMessages
            );
        }

        @Override
        public boolean accept(Pointer identity) {
            return api.SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser(messages, identity) != 0;
        }

        @Override
        public void closeSession(Pointer identity) {
            api.SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser(messages, identity);
        }

        @Override
        public int getSessionConnectionInfo(Pointer identity, Pointer realTimeStatus) {
            return api.SteamAPI_ISteamNetworkingMessages_GetSessionConnectionInfo(
                    messages,
                    identity,
                    null,
                    realTimeStatus
            );
        }

        @Override
        public void initializeRelayNetworkAccess() {
            api.SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(utils);
        }

        @Override
        public void releaseMessage(Pointer message) {
            api.SteamAPI_SteamNetworkingMessage_t_Release(message);
        }

        @Override
        public void setCallbacks(SessionRequestCallback request, SessionFailedCallback failure) {
            api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionRequest(utils, request);
            api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionFailed(utils, failure);
        }

        @Override
        public void setDebugOutput(int detailLevel, DebugOutputCallback callback) {
            api.SteamAPI_ISteamNetworkingUtils_SetDebugOutputFunction(utils, detailLevel, callback);
        }
    }
}
