package link.e4steam;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorTest {
    @Test
    void shortMessageUsesTheRootCauseWithoutChatControlCharacters() {
        var failure = new IOException(
                "Steam initialization failed",
                new IOException("Steam is not running\r\nor the current user is not signed in")
        );

        String message = Doctor.shortMessage(failure);

        assertEquals("Steam is not running or the current user is not signed in", message);
        assertFalse(message.contains("\r"));
        assertFalse(message.contains("\n"));
        assertFalse(message.contains("at link.e4steam"));
    }

    @Test
    void shortMessageLimitsUntrustedExceptionTextForChat() {
        String message = Doctor.shortMessage(new IOException("x".repeat(500)));

        assertEquals(240, message.length());
        assertTrue(message.endsWith("..."));
    }
}
