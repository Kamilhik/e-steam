package link.e4steam.steam;

import com.codedisaster.steamworks.SteamResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SteamOutboundQueueTest {
    @Test
    void rejectsOverflowWithoutStealingAnotherCategoryCapacity() {
        SteamOutboundQueue<String> queue = new SteamOutboundQueue<>(5, 2, 1, 1, 1);

        assertTrue(queue.offerData(1, 1, new byte[]{1}, "bridge"));
        assertTrue(queue.offerData(1, 1, new byte[]{2}, "bridge"));
        assertFalse(queue.offerData(1, 1, new byte[]{3}, "bridge"));

        assertTrue(queue.offerDatagram(1, 1, new byte[]{4}, "bridge"));
        assertFalse(queue.offerDatagram(1, 1, new byte[]{5}, "bridge"));
        assertTrue(queue.offerControl(1, 1, new byte[]{6}, SteamOutboundQueue.Kind.RESET, null));
    }

    @Test
    void purgeAndClearReturnCapacityForRestart() {
        SteamOutboundQueue<String> queue = new SteamOutboundQueue<>(3, 1, 1, 1, 1);
        String first = new String("first");
        String second = new String("second");

        assertTrue(queue.offerData(1, 1, new byte[]{1}, first));
        assertTrue(queue.offerDatagram(2, 2, new byte[]{2}, second));
        queue.purge(first);
        assertTrue(queue.offerData(1, 3, new byte[]{3}, first));
        queue.clear();
        assertTrue(queue.isEmpty());
        assertTrue(queue.offerDatagram(2, 4, new byte[]{4}, second));
    }

    @Test
    void pollPreservesOrderAndReleasesPermit() {
        SteamOutboundQueue<String> queue = new SteamOutboundQueue<>(2, 1, 1, 1, 1);
        assertTrue(queue.offerData(9, 10, new byte[]{7}, "bridge"));
        SteamOutboundQueue.Packet<String> packet = queue.poll();
        assertNotNull(packet);
        assertEquals(9, packet.remoteSteamId());
        assertEquals(SteamOutboundQueue.Kind.DATA, packet.kind());
        assertTrue(queue.offerData(9, 11, new byte[]{8}, "bridge"));
    }

    @Test
    void steamBackpressureResultsAreRetriedInsteadOfDisconnectingMinecraft() {
        assertTrue(SteamRuntime.isRetryableSendFailure(SteamResult.LimitExceeded));
        assertTrue(SteamRuntime.isRetryableSendFailure(SteamResult.Busy));
        assertTrue(SteamRuntime.isRetryableSendFailure(SteamResult.NoConnection));
        assertTrue(SteamRuntime.isRetryableSendFailure(SteamResult.ServiceUnavailable));
        assertFalse(SteamRuntime.isRetryableSendFailure(SteamResult.InvalidParam));
    }
}
