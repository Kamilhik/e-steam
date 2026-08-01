package link.e4steam.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamNetworking;

import java.nio.ByteBuffer;

/** Thin boundary around SteamNetworking packet and peer-session operations. */
final class SteamPacketTransport implements AutoCloseable {
    record Received(long remoteSteamId, int size) {
    }

    private final SteamNetworking networking;

    SteamPacketTransport(SteamNetworking networking) {
        this.networking = networking;
    }

    boolean enableRelayFallback() {
        return networking.allowP2PPacketRelay(true);
    }

    boolean send(long remoteSteamId, ByteBuffer payload, boolean unreliable, int channel)
            throws SteamException {
        return networking.sendP2PPacket(
                SteamID.createFromNativeHandle(remoteSteamId),
                payload,
                unreliable
                        ? SteamNetworking.P2PSend.UnreliableNoDelay
                        : SteamNetworking.P2PSend.Reliable,
                channel
        );
    }

    int availablePacketSize(int channel) {
        int[] size = new int[1];
        return networking.isP2PPacketAvailable(channel, size) ? size[0] : 0;
    }

    Received receive(ByteBuffer target, int channel) throws SteamException {
        SteamID remote = new SteamID();
        int read = networking.readP2PPacket(remote, target, channel);
        return new Received(SteamNativeHandle.getNativeHandle(remote), read);
    }

    boolean accept(long remoteSteamId) {
        return networking.acceptP2PSessionWithUser(SteamID.createFromNativeHandle(remoteSteamId));
    }

    void closePeer(long remoteSteamId) {
        networking.closeP2PSessionWithUser(SteamID.createFromNativeHandle(remoteSteamId));
    }

    boolean hasQueuedPackets(long remoteSteamId) {
        SteamNetworking.P2PSessionState state = new SteamNetworking.P2PSessionState();
        return networking.getP2PSessionState(SteamID.createFromNativeHandle(remoteSteamId), state)
                && (state.isConnecting()
                || state.getPacketsQueuedForSend() > 0
                || state.getBytesQueuedForSend() > 0);
    }

    @Override
    public void close() {
        networking.dispose();
    }
}
