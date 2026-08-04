package link.e4steam.steam;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteamNetworkingMessagesTransportTest {
    private static final long REMOTE_ID = 76561198000000001L;

    @Test
    void sendsWithSteamNetworkingSocketsEquivalentFlags() throws Exception {
        FakeNativeAccess nativeAccess = new FakeNativeAccess();
        SteamNetworkingMessagesTransport transport = transport(nativeAccess);
        ByteBuffer buffer = ByteBuffer.allocateDirect(4);
        buffer.put(new byte[]{1, 2, 3, 4}).flip();

        assertTrue(transport.send(REMOTE_ID, buffer, false, 480));
        assertEquals(9, nativeAccess.sentFlags); // ReliableNoNagle matches legacy P2PSend.Reliable.
        assertEquals(REMOTE_ID, nativeAccess.sentSteamId);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, nativeAccess.sentPayload);

        buffer.position(1);
        assertTrue(transport.send(REMOTE_ID, buffer, true, 480));
        assertEquals(5, nativeAccess.sentFlags); // UnreliableNoDelay.
        assertArrayEquals(new byte[]{2, 3, 4}, nativeAccess.sentPayload);
        transport.close();
    }

    @Test
    void receivesAndReleasesNativeMessage() throws Exception {
        FakeNativeAccess nativeAccess = new FakeNativeAccess();
        nativeAccess.queueMessage(REMOTE_ID, new byte[]{9, 8, 7});
        SteamNetworkingMessagesTransport transport = transport(nativeAccess);

        assertEquals(3, transport.availablePacketSize(480));
        ByteBuffer target = ByteBuffer.allocateDirect(16);
        SteamNetworkingMessagesTransport.Received received = transport.receive(target, 480);

        assertEquals(REMOTE_ID, received.remoteSteamId());
        assertEquals(3, received.size());
        target.flip();
        byte[] payload = new byte[target.remaining()];
        target.get(payload);
        assertArrayEquals(new byte[]{9, 8, 7}, payload);
        assertEquals(1, nativeAccess.releaseCount);
        transport.close();
    }

    @Test
    void forwardsSessionCallbacksAndRejectsNonSteamIdentities() {
        FakeNativeAccess nativeAccess = new FakeNativeAccess();
        List<Long> requests = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        SteamNetworkingMessagesTransport transport = new SteamNetworkingMessagesTransport(
                nativeAccess,
                new SteamNetworkingMessagesTransport.SessionListener() {
                    @Override
                    public void onSessionRequest(long remoteSteamId) {
                        requests.add(remoteSteamId);
                    }

                    @Override
                    public void onSessionFailed(long remoteSteamId, int endReason, String detail) {
                        failures.add(remoteSteamId + ":" + endReason + ":" + detail);
                    }
                }
        );

        Memory request = SteamNetworkingMessagesTransport.newIdentity(REMOTE_ID);
        nativeAccess.requestCallback.invoke(request);
        request.setInt(0, 3);
        nativeAccess.requestCallback.invoke(request);

        Memory failure = new Memory(512);
        failure.clear();
        Memory identity = SteamNetworkingMessagesTransport.newIdentity(REMOTE_ID);
        failure.write(0, identity.getByteArray(0, 136), 0, 136);
        failure.setInt(176, 5003);
        byte[] detail = "connection timed out".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        failure.write(180, detail, 0, detail.length);
        nativeAccess.failedCallback.invoke(failure);

        assertEquals(List.of(REMOTE_ID), requests);
        assertEquals(List.of(REMOTE_ID + ":5003:connection timed out"), failures);
        transport.close();
    }

    @Test
    void reportsConnectingAndQueuedSessionData() {
        FakeNativeAccess nativeAccess = new FakeNativeAccess();
        SteamNetworkingMessagesTransport transport = transport(nativeAccess);

        nativeAccess.connectionState = 1;
        assertTrue(transport.hasQueuedPackets(REMOTE_ID));
        nativeAccess.connectionState = 3;
        nativeAccess.pendingReliable = 12;
        assertTrue(transport.hasQueuedPackets(REMOTE_ID));
        nativeAccess.pendingReliable = 0;
        assertFalse(transport.hasQueuedPackets(REMOTE_ID));
        transport.close();
    }

    private static SteamNetworkingMessagesTransport transport(FakeNativeAccess nativeAccess) {
        return new SteamNetworkingMessagesTransport(
                nativeAccess,
                new SteamNetworkingMessagesTransport.SessionListener() {
                    @Override
                    public void onSessionRequest(long remoteSteamId) {
                    }

                    @Override
                    public void onSessionFailed(long remoteSteamId, int endReason, String detail) {
                    }
                }
        );
    }

    private static final class FakeNativeAccess implements SteamNetworkingMessagesTransport.NativeAccess {
        private SteamNetworkingMessagesTransport.SessionRequestCallback requestCallback;
        private SteamNetworkingMessagesTransport.SessionFailedCallback failedCallback;
        private Memory queuedMessage;
        private Memory deliveredMessage;
        private Memory queuedPayload;
        private byte[] sentPayload;
        private long sentSteamId;
        private int sentFlags;
        private int releaseCount;
        private int connectionState;
        private int pendingReliable;

        @Override
        public int send(Pointer identity, Pointer data, int size, int flags, int channel) {
            sentSteamId = SteamNetworkingMessagesTransport.readSteamId(identity);
            sentPayload = data.getByteArray(0, size);
            sentFlags = flags;
            return 1;
        }

        @Override
        public int receive(int channel, Pointer[] messages, int maxMessages) {
            if (queuedMessage == null) {
                return 0;
            }
            deliveredMessage = queuedMessage;
            messages[0] = deliveredMessage;
            queuedMessage = null;
            return 1;
        }

        @Override
        public boolean accept(Pointer identity) {
            return true;
        }

        @Override
        public void closeSession(Pointer identity) {
        }

        @Override
        public int getSessionConnectionInfo(Pointer identity, Pointer realTimeStatus) {
            realTimeStatus.setInt(40, pendingReliable);
            return connectionState;
        }

        @Override
        public void initializeRelayNetworkAccess() {
        }

        @Override
        public void releaseMessage(Pointer message) {
            releaseCount++;
            deliveredMessage = null;
            queuedPayload = null;
        }

        @Override
        public void setCallbacks(
                SteamNetworkingMessagesTransport.SessionRequestCallback request,
                SteamNetworkingMessagesTransport.SessionFailedCallback failure
        ) {
            requestCallback = request;
            failedCallback = failure;
        }

        @Override
        public void setDebugOutput(
                int detailLevel,
                SteamNetworkingMessagesTransport.DebugOutputCallback callback
        ) {
        }

        private void queueMessage(long remoteSteamId, byte[] payload) {
            queuedPayload = new Memory(payload.length);
            queuedPayload.write(0, payload, 0, payload.length);
            queuedMessage = new Memory(216);
            queuedMessage.clear();
            queuedMessage.setPointer(0, queuedPayload);
            queuedMessage.setInt(8, payload.length);
            Memory identity = SteamNetworkingMessagesTransport.newIdentity(remoteSteamId);
            queuedMessage.write(16, identity.getByteArray(0, 136), 0, 136);
        }
    }
}
